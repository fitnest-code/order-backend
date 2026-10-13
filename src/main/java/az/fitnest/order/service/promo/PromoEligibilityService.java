package az.fitnest.order.service.promo;

import az.fitnest.order.dto.promo.*;
import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.exception.ConflictException;
import az.fitnest.order.model.entity.promo.*;
import az.fitnest.order.repository.promo.*;
import az.fitnest.order.util.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PromoEligibilityService {

    private final PromoGrantRepository grantRepository;
    private final PromoCampaignRepository campaignRepository;
    private final PromoCampaignTranslationRepository translationRepository;
    private final PromoCampaignPopupRepository popupRepository;
    private final PromoPartnerRepository partnerRepository;
    private final PromoPhoneHasher phoneHasher;

    private static final Set<PromoGrantStatus> USABLE = EnumSet.of(PromoGrantStatus.GRANTED, PromoGrantStatus.RESERVED);

    @Transactional(readOnly = true)
    public MobilePromoPopupResponse pendingPopup(String language) {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) return null;
        Optional<PromoGrant> grantOpt = grantRepository.findFirstByUserIdAndPopupStatusOrderByCreatedAtDesc(userId, PromoPopupStatus.PENDING);
        if (grantOpt.isEmpty()) return null;
        return buildPopup(grantOpt.get(), language);
    }

    private MobilePromoPopupResponse buildPopup(PromoGrant grant, String language) {
        PromoCampaign campaign = campaignRepository.findById(grant.getCampaignId()).orElse(null);
        if (campaign == null) return null;
        String normalized = normalizeLang(language);
        PromoCampaignTranslation tr = translationRepository.findByCampaignId(campaign.getId()).stream()
                .filter(t -> t.getLocale().name().equals(normalized))
                .findFirst()
                .or(() -> translationRepository.findByCampaignId(campaign.getId()).stream()
                        .filter(t -> t.getLocale() == PromoLocale.az).findFirst())
                .orElse(null);
        if (tr == null) return null;

        PopupImages images = resolveImages(campaign);
        PromoCampaignPopup popup = popupRepository.findByCampaignId(campaign.getId()).orElse(null);

        return MobilePromoPopupResponse.builder()
                .grantId(grant.getId())
                .campaignId(campaign.getId())
                .code(campaign.getCode())
                .title(tr.getPopupTitle())
                .body(fillPlaceholders(tr.getPopupBody(), campaign))
                .imageUrl(images.imageUrl)
                .endsAt(campaign.getEndsAt())
                .cta(popup != null ? MobilePromoCtaResponse.builder()
                        .label(tr.getCtaLabel())
                        .target(popup.getCtaTarget().name())
                        .planId(popup.getPlanId())
                        .url(popup.getUrl())
                        .build() : null)
                .partner(buildPartner(campaign))
                .build();
    }

    @Transactional(readOnly = true)
    public List<MobilePromoActiveItemResponse> activeCodes(UUID planId, String language) {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) return List.of();
        OffsetDateTime now = OffsetDateTime.now();

        List<PromoGrant> grants = grantRepository.findByUserIdAndPopupStatusOrderByCreatedAtDesc(userId, PromoPopupStatus.PENDING)
                .stream().filter(g -> USABLE.contains(g.getStatus())).collect(Collectors.toList());
        // Also SEEN popup grants that are still usable
        grants.addAll(grantRepository.findByUserIdAndPopupStatusOrderByCreatedAtDesc(userId, PromoPopupStatus.SEEN)
                .stream().filter(g -> USABLE.contains(g.getStatus())).collect(Collectors.toList()));

        List<PromoGrant> unique = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (PromoGrant g : grants) {
            if (seen.add(g.getId())) unique.add(g);
        }

        List<MobilePromoActiveItemResponse> items = new ArrayList<>();
        for (PromoGrant g : unique) {
            PromoCampaign c = campaignRepository.findById(g.getCampaignId()).orElse(null);
            if (c == null) continue;
            if (c.getStatus() != PromoCampaignStatus.ACTIVE) continue;
            if (c.getEndsAt().isBefore(now) || c.getStartsAt().isAfter(now)) continue;
            if (c.getTotalLimit() != null && c.getRedeemedCount() >= c.getTotalLimit()) continue;
            if (planId != null && c.getPlanIds() != null && c.getPlanIds().length > 0
                    && !Arrays.asList(c.getPlanIds()).contains(planId)) {
                continue;
            }
            String normalized = normalizeLang(language);
            PromoCampaignTranslation tr = translationRepository.findByCampaignId(c.getId()).stream()
                    .filter(t -> t.getLocale().name().equals(normalized))
                    .findFirst()
                    .or(() -> translationRepository.findByCampaignId(c.getId()).stream()
                            .filter(t -> t.getLocale() == PromoLocale.az).findFirst())
                    .orElse(null);
            String label = tr != null && tr.getCouponLabel() != null
                    ? tr.getCouponLabel()
                    : (tr != null && tr.getCardLabel() != null ? tr.getCardLabel() : buildDiscountLabel(c));

            items.add(MobilePromoActiveItemResponse.builder()
                    .code(c.getCode())
                    .label(label)
                    .discountType(c.getDiscountType().name())
                    .discountValue(c.getDiscountValue())
                    .discountAmount(null)
                    .endsAt(c.getEndsAt())
                    .banner(MobilePromoBannerResponse.builder()
                            .imageUrl(resolveImages(c).bannerUrl)
                            .build())
                    .partner(buildPartner(c))
                    .build());
        }

        // BR-20: sort by largest discount -> priority -> earliest end date
        items.sort(Comparator
                .comparing((MobilePromoActiveItemResponse i) -> discountRank(i.getDiscountType(), i.getDiscountValue())).reversed()
                .thenComparing(MobilePromoActiveItemResponse::getEndsAt));
        return items;
    }

    private double discountRank(String type, BigDecimal value) {
        if (type == null || value == null) return 0;
        try {
            return value.doubleValue();
        } catch (Exception e) { return 0; }
    }

    private String buildDiscountLabel(PromoCampaign c) {
        if (c.getDiscountType() == PromoDiscountType.PERCENT) {
            return c.getDiscountValue().stripTrailingZeros().toPlainString() + "% endirim";
        }
        return c.getDiscountValue() + " AZN endirim";
    }

    private String fillPlaceholders(String body, PromoCampaign c) {
        if (body == null) return null;
        String discount = c.getDiscountType() == PromoDiscountType.PERCENT
                ? c.getDiscountValue().stripTrailingZeros().toPlainString() + "%"
                : c.getDiscountValue().toPlainString() + " AZN";
        return body
                .replace("{code}", c.getCode())
                .replace("{discount}", discount)
                .replace("{endDate}", formatDate(c.getEndsAt()))
                .replace("{partner}", partnerName(c));
    }

    private String formatDate(OffsetDateTime d) {
        if (d == null) return "";
        return d.getDayOfMonth() + " " + monthName(d.getMonthValue()) + " " + d.getYear();
    }

    private String monthName(int m) {
        String[] az = {"yanvar","fevral","mart","aprel","may","iyun","iyul","avqust","sentyabr","oktyabr","noyabr","dekabr"};
        if (m >= 1 && m <= 12) return az[m-1];
        return "";
    }

    private String partnerName(PromoCampaign c) {
        if (c.getPartnerId() == null) return "FitNest";
        return partnerRepository.findById(c.getPartnerId()).map(PromoPartner::getName).orElse("FitNest");
    }

    private MobilePromoPartnerResponse buildPartner(PromoCampaign c) {
        if (c.getPartnerId() == null) return null;
        PromoPartner p = partnerRepository.findById(c.getPartnerId()).orElse(null);
        if (p == null) return null;
        String brandColor = c.getBrandColorOverride() != null ? c.getBrandColorOverride() : p.getBrandColor();
        String logo = c.getLogoUrlOverride() != null ? c.getLogoUrlOverride() : p.getLogoUrl();
        return MobilePromoPartnerResponse.builder()
                .id(p.getId())
                .name(p.getName())
                .logoUrl(logo)
                .brandColor(brandColor)
                .textColor(pickTextColor(brandColor))
                .build();
    }

    private String pickTextColor(String brandColor) {
        if (brandColor == null || !brandColor.matches("^#[0-9A-Fa-f]{6}$")) return "#FFFFFF";
        int r = Integer.parseInt(brandColor.substring(1, 3), 16);
        int g = Integer.parseInt(brandColor.substring(3, 5), 16);
        int b = Integer.parseInt(brandColor.substring(5, 7), 16);
        double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
        return luminance > 0.5 ? "#000000" : "#FFFFFF";
    }

    private String normalizeLang(String language) {
        if (language == null) return "az";
        String l = language.toLowerCase();
        if (l.startsWith("ru")) return "ru";
        if (l.startsWith("en")) return "en";
        return "az";
    }

    private PopupImages resolveImages(PromoCampaign c) {
        String imageUrl = null;
        String bannerUrl = null;
        popupRepository.findByCampaignId(c.getId()).ifPresent(p -> {
            // handled below
        });
        PromoCampaignPopup popup = popupRepository.findByCampaignId(c.getId()).orElse(null);
        if (popup != null) imageUrl = popup.getImageUrl();
        if (imageUrl == null) {
            if (c.getLogoUrlOverride() != null) imageUrl = c.getLogoUrlOverride();
            else if (c.getPartnerId() != null) {
                imageUrl = partnerRepository.findById(c.getPartnerId()).map(PromoPartner::getLogoUrl).orElse(null);
            }
        }
        if (c.getLogoUrlOverride() != null) bannerUrl = c.getLogoUrlOverride();
        else if (c.getPartnerId() != null) {
            bannerUrl = partnerRepository.findById(c.getPartnerId()).map(PromoPartner::getLogoUrl).orElse(null);
        }
        return new PopupImages(imageUrl, bannerUrl);
    }

    private record PopupImages(String imageUrl, String bannerUrl) {}
}
