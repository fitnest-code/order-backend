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
    private final PromoCampaignMediaRepository mediaRepository;
    private final az.fitnest.order.repository.SubscriptionRepository subscriptionRepository;

    private static final Set<PromoGrantStatus> USABLE = EnumSet.of(PromoGrantStatus.GRANTED, PromoGrantStatus.RESERVED);
    private static final java.time.ZoneId BAKU_ZONE = java.time.ZoneId.of("Asia/Baku");

    @Transactional(readOnly = true)
    public MobilePromoPopupResponse pendingPopup(String language) {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) return null;
        // B17: users with an active subscription see no popup (grant is NOT
        // marked seen, so it appears once the subscription lapses).
        if (hasActiveSubscription(userId)) return null;
        OffsetDateTime now = OffsetDateTime.now();
        // At most one popup per app open; queue by priority, then grant date (BR-22).
        // Only ACTIVE campaigns inside their date window are eligible.
        return grantRepository.findByUserIdAndPopupStatusOrderByCreatedAtDesc(userId, PromoPopupStatus.PENDING)
                .stream()
                .sorted(Comparator.comparing(PromoGrant::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(g -> Map.entry(g, campaignRepository.findById(g.getCampaignId()).orElse(null)))
                .filter(e -> e.getValue() != null
                        && e.getValue().getStatus() == PromoCampaignStatus.ACTIVE
                        && !e.getValue().getStartsAt().isAfter(now)
                        && !e.getValue().getEndsAt().isBefore(now)
                        && (e.getValue().getTotalLimit() == null
                                || e.getValue().getRedeemedCount() < e.getValue().getTotalLimit()))
                .sorted((a, b) -> Integer.compare(
                        b.getValue().getPriority() != null ? b.getValue().getPriority() : 0,
                        a.getValue().getPriority() != null ? a.getValue().getPriority() : 0))
                .map(e -> buildPopup(e.getKey(), language))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * B16/B17 shared definition: subscription counts as active while its
     * duration has not ended and its entry limit is not exhausted.
     */
    @Transactional(readOnly = true)
    public boolean hasActiveSubscription(Long userId) {
        if (userId == null) return false;
        java.time.LocalDateTime now = java.time.LocalDateTime.now(BAKU_ZONE);
        return subscriptionRepository.findByUserIdAndStatusIn(userId, List.of("ACTIVE", "FROZEN"))
                .stream()
                .anyMatch(s -> (s.getEndAt() == null || s.getEndAt().isAfter(now))
                        && (s.getRemainingLimit() == null || s.getRemainingLimit() > 0));
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
                        .packageId(popup.getPackageId())
                        .url(popup.getUrl())
                        .campaignId(campaign.getId())
                        .code(campaign.getCode())
                        .build() : null)
                .partner(buildPartner(campaign))
                .build();
    }

    @Transactional(readOnly = true)
    public List<MobilePromoActiveItemResponse> activeCodes(String planId, String language) {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) return List.of();
        // B16: banner list (no planId) is hidden while the user holds an active
        // subscription - even one bought with a promo code. It reappears once the
        // subscription expires or its entry limit is exhausted.
        if ((planId == null || planId.isBlank()) && hasActiveSubscription(userId)) {
            return List.of();
        }
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

        List<Map.Entry<PromoCampaign, MobilePromoActiveItemResponse>> items = new ArrayList<>();
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

            PromoCampaignPopup popupCfg = popupRepository.findByCampaignId(c.getId()).orElse(null);
            items.add(Map.entry(c, MobilePromoActiveItemResponse.builder()
                    .code(c.getCode())
                    .label(label)
                    .discountType(c.getDiscountType().name())
                    .discountValue(c.getDiscountValue())
                    .discountAmount(null)
                    .endsAt(c.getEndsAt())
                    .priority(c.getPriority() != null ? c.getPriority() : 0)
                    .banner(MobilePromoBannerResponse.builder()
                            .imageUrl(resolveBannerUrl(c, normalized))
                            .build())
                    .partner(buildPartner(c))
                    .cta(popupCfg != null ? MobilePromoCtaResponse.builder()
                            .label(tr != null && tr.getCtaLabel() != null ? tr.getCtaLabel() : null)
                            .target(popupCfg.getCtaTarget().name())
                            .packageId(popupCfg.getPackageId())
                            .url(popupCfg.getUrl())
                            .campaignId(c.getId())
                            .code(c.getCode())
                            .build() : null)
                    .detail(MobilePromoDetailResponse.builder()
                            .heroImageUrl(resolveHeroUrl(c, normalized))
                            .durationSec(popupCfg != null && popupCfg.getDurationSec() != null
                                    ? popupCfg.getDurationSec() : 7)
                            .cta(MobilePromoCtaResponse.builder()
                                    .label(tr != null && tr.getCtaLabel() != null ? tr.getCtaLabel() : null)
                                    .target(PromoCtaTarget.PLANS.name())
                                    .packageId(null)
                                    .url(null)
                                    .campaignId(c.getId())
                                    .code(c.getCode())
                                    .build())
                            .build())
                    .build()));
        }

        // BR-20: largest discount -> priority -> earliest end date
        items.sort(Comparator
                .comparing((Map.Entry<PromoCampaign, MobilePromoActiveItemResponse> e) ->
                        discountRank(e.getValue().getDiscountType(), e.getValue().getDiscountValue())).reversed()
                .thenComparing(e -> e.getKey().getPriority() != null ? e.getKey().getPriority() : 0,
                        Comparator.reverseOrder())
                .thenComparing(e -> e.getValue().getEndsAt(),
                        Comparator.nullsLast(Comparator.naturalOrder())));
        return items.stream().map(Map.Entry::getValue).collect(Collectors.toList());
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
        // B2: code and discount are wrapped in **bold** spans; the deadline is
        // NOT part of the body - the app formats endsAt itself on a separate line.
        String discount = c.getDiscountType() == PromoDiscountType.PERCENT
                ? c.getDiscountValue().stripTrailingZeros().toPlainString() + "%"
                : c.getDiscountValue().toPlainString() + " AZN";
        return body
                .replace("{code}", "**" + c.getCode() + "**")
                .replace("{discount}", "**" + discount + " endirim**")
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
        String logoOnDark = c.getLogoOnDarkUrlOverride() != null ? c.getLogoOnDarkUrlOverride() : p.getLogoOnDarkUrl();
        return MobilePromoPartnerResponse.builder()
                .id(p.getId())
                .name(p.getName())
                .logoUrl(logo)
                .logoOnDarkUrl(logoOnDark)
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

    /**
     * B3: banner image is localized (az/en/ru versions, 987x432 @3x).
     * Falls back to az, then to partner logo.
     */
    private String resolveBannerUrl(PromoCampaign c, String lang) {
        List<PromoCampaignMedia> media = mediaRepository.findByCampaignId(c.getId());
        String url = pickMedia(media, lang, true);
        if (url == null) url = pickMedia(media, "az", true);
        if (url == null) {
            if (c.getLogoUrlOverride() != null) url = c.getLogoUrlOverride();
            else if (c.getPartnerId() != null) {
                url = partnerRepository.findById(c.getPartnerId()).map(PromoPartner::getLogoUrl).orElse(null);
            }
        }
        return url;
    }

    /**
     * B5: M3 story hero image, localized (402x950 @3x). az fallback.
     */
    private String resolveHeroUrl(PromoCampaign c, String lang) {
        List<PromoCampaignMedia> media = mediaRepository.findByCampaignId(c.getId());
        String url = pickMedia(media, lang, false);
        if (url == null) url = pickMedia(media, "az", false);
        return url;
    }

    private String pickMedia(List<PromoCampaignMedia> media, String lang, boolean banner) {
        for (PromoCampaignMedia m : media) {
            if (m.getLocale().name().equals(lang)) {
                String url = banner ? m.getBannerImageUrl() : m.getHeroImageUrl();
                if (url != null && !url.isBlank()) return url;
            }
        }
        return null;
    }

    private PopupImages resolveImages(PromoCampaign c) {
        String imageUrl = null;
        String bannerUrl = null;
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
