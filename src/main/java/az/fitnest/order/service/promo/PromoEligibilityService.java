package az.fitnest.order.service.promo;

import az.fitnest.order.dto.promo.*;
import az.fitnest.order.model.entity.promo.*;
import az.fitnest.order.repository.SubscriptionRepository;
import az.fitnest.order.repository.promo.*;
import az.fitnest.order.util.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;
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
    private final PromoCampaignMediaRepository mediaRepository;
    private final SubscriptionRepository subscriptionRepository;

    private static final Set<PromoGrantStatus> USABLE = EnumSet.of(PromoGrantStatus.GRANTED, PromoGrantStatus.RESERVED);
    private static final ZoneId BAKU_ZONE = ZoneId.of("Asia/Baku");
    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

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
                .map(g -> Map.entry(g, campaignRepository.findById(g.getCampaignId()).orElse(null)))
                .filter(e -> isPopupEligible(e.getValue(), now))
                .sorted(Comparator
                        .comparingInt((Map.Entry<PromoGrant, PromoCampaign> e) -> priorityOf(e.getValue()))
                        .reversed()
                        .thenComparing(e -> e.getKey().getCreatedAt(),
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .map(e -> buildPopup(e.getKey(), e.getValue(), language))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private boolean isPopupEligible(PromoCampaign c, OffsetDateTime now) {
        return c != null
                && c.getStatus() == PromoCampaignStatus.ACTIVE
                && !c.getStartsAt().isAfter(now)
                && !c.getEndsAt().isBefore(now)
                && (c.getTotalLimit() == null || c.getRedeemedCount() < c.getTotalLimit());
    }

    private int priorityOf(PromoCampaign c) {
        return c.getPriority() != null ? c.getPriority() : 0;
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

    private MobilePromoPopupResponse buildPopup(PromoGrant grant, PromoCampaign campaign, String language) {
        if (campaign == null) return null;
        PromoCampaignTranslation tr = translationFor(campaign.getId(), language);
        if (tr == null) return null;

        PromoCampaignPopup popup = popupRepository.findByCampaignId(campaign.getId()).orElse(null);
        PromoPartner partner = campaign.getPartnerId() != null
                ? partnerRepository.findById(campaign.getPartnerId()).orElse(null) : null;

        return MobilePromoPopupResponse.builder()
                .grantId(grant.getId())
                .campaignId(campaign.getId())
                .code(campaign.getCode())
                .title(tr.getPopupTitle())
                .body(fillPlaceholders(tr.getPopupBody(), campaign))
                .imageUrl(resolvePopupImage(campaign, popup, partner))
                .endsAt(campaign.getEndsAt())
                .cta(popup != null ? MobilePromoCtaResponse.builder()
                        .label(tr.getCtaLabel())
                        .target(popup.getCtaTarget().name())
                        .packageId(popup.getPackageId())
                        .url(popup.getUrl())
                        .campaignId(campaign.getId())
                        .code(campaign.getCode())
                        .build() : null)
                .partner(partnerBlock(campaign, partner))
                .build();
    }

    private String resolvePopupImage(PromoCampaign c, PromoCampaignPopup popup, PromoPartner partner) {
        if (popup != null && popup.getImageUrl() != null) return popup.getImageUrl();
        if (c.getLogoUrlOverride() != null) return c.getLogoUrlOverride();
        return partner != null ? partner.getLogoUrl() : null;
    }

    private static MobilePromoPartnerResponse partnerBlock(PromoCampaign c, PromoPartner p) {
        if (c.getPartnerId() == null || p == null) return null;
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

    /** Single fetch with az fallback (was two queries). */
    private PromoCampaignTranslation translationFor(UUID campaignId, String language) {
        String lang = normalizeLang(language);
        PromoCampaignTranslation az = null;
        for (PromoCampaignTranslation t : translationRepository.findByCampaignId(campaignId)) {
            if (t.getLocale().name().equals(lang)) return t;
            if (t.getLocale() == PromoLocale.az) az = t;
        }
        return az;
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
        String lang = normalizeLang(language);

        List<PromoGrant> grants = grantRepository.findByUserIdAndPopupStatusOrderByCreatedAtDesc(userId, PromoPopupStatus.PENDING)
                .stream().filter(g -> USABLE.contains(g.getStatus())).collect(Collectors.toList());
        // Also SEEN popup grants that are still usable
        grants.addAll(grantRepository.findByUserIdAndPopupStatusOrderByCreatedAtDesc(userId, PromoPopupStatus.SEEN)
                .stream().filter(g -> USABLE.contains(g.getStatus())).collect(Collectors.toList()));

        // Dedupe grants, then batch-load everything (constant query count no
        // matter how many grants: ~7 queries for the whole call, p95 <150ms).
        Set<UUID> seenGrant = new HashSet<>();
        List<UUID> campaignIds = new ArrayList<>();
        Map<UUID, PromoGrant> grantByCampaign = new HashMap<>();
        for (PromoGrant g : grants) {
            if (seenGrant.add(g.getId()) && !grantByCampaign.containsKey(g.getCampaignId())) {
                grantByCampaign.put(g.getCampaignId(), g);
                campaignIds.add(g.getCampaignId());
            }
        }
        if (campaignIds.isEmpty()) return List.of();

        Map<UUID, PromoCampaign> campaigns = campaignRepository.findAllById(campaignIds).stream()
                .collect(Collectors.toMap(PromoCampaign::getId, c -> c, (a, b) -> a));
        Map<UUID, List<PromoCampaignTranslation>> translations = translationRepository.findByCampaignIdIn(campaignIds)
                .stream().collect(Collectors.groupingBy(PromoCampaignTranslation::getCampaignId));
        Map<UUID, PromoCampaignPopup> popups = popupRepository.findByCampaignIdIn(campaignIds)
                .stream().collect(Collectors.toMap(PromoCampaignPopup::getCampaignId, p -> p, (a, b) -> a));
        Map<UUID, List<PromoCampaignMedia>> media = mediaRepository.findByCampaignIdIn(campaignIds)
                .stream().collect(Collectors.groupingBy(PromoCampaignMedia::getCampaignId));
        Map<UUID, PromoPartner> partners = partnerRepository.findAllById(campaigns.values().stream()
                        .map(PromoCampaign::getPartnerId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(PromoPartner::getId, p -> p, (a, b) -> a));
        PromoView view = new PromoView(translations, popups, media, partners);

        List<Map.Entry<PromoCampaign, MobilePromoActiveItemResponse>> items = new ArrayList<>();
        for (PromoGrant g : grantByCampaign.values()) {
            PromoCampaign c = campaigns.get(g.getCampaignId());
            if (c == null) continue;
            if (c.getStatus() != PromoCampaignStatus.ACTIVE) continue;
            if (c.getEndsAt().isBefore(now) || c.getStartsAt().isAfter(now)) continue;
            if (c.getTotalLimit() != null && c.getRedeemedCount() >= c.getTotalLimit()) continue;
            if (planId != null && c.getPlanIds() != null && c.getPlanIds().length > 0
                    && !Arrays.asList(c.getPlanIds()).contains(planId)) {
                continue;
            }
            PromoCampaignTranslation tr = view.translation(c.getId(), lang);
            String label = tr != null && tr.getCouponLabel() != null
                    ? tr.getCouponLabel()
                    : (tr != null && tr.getCardLabel() != null ? tr.getCardLabel() : buildDiscountLabel(c));

            PromoCampaignPopup popupCfg = view.popups.get(c.getId());
            items.add(Map.entry(c, MobilePromoActiveItemResponse.builder()
                    .code(c.getCode())
                    .label(label)
                    .discountType(c.getDiscountType().name())
                    .discountValue(c.getDiscountValue())
                    .discountAmount(null)
                    .endsAt(c.getEndsAt())
                    .priority(c.getPriority() != null ? c.getPriority() : 0)
                    .banner(MobilePromoBannerResponse.builder()
                            .imageUrl(view.bannerUrl(c, lang))
                            .build())
                    .partner(view.partner(c))
                    .cta(popupCfg != null ? MobilePromoCtaResponse.builder()
                            .label(tr != null && tr.getCtaLabel() != null ? tr.getCtaLabel() : null)
                            .target(popupCfg.getCtaTarget().name())
                            .packageId(popupCfg.getPackageId())
                            .url(popupCfg.getUrl())
                            .campaignId(c.getId())
                            .code(c.getCode())
                            .build() : null)
                    .detail(MobilePromoDetailResponse.builder()
                            .heroImageUrl(view.heroUrl(c, lang))
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
                        e.getValue().getDiscountValue() != null
                                ? e.getValue().getDiscountValue().doubleValue() : 0).reversed()
                .thenComparing(e -> e.getKey().getPriority() != null ? e.getKey().getPriority() : 0,
                        Comparator.reverseOrder())
                .thenComparing(e -> e.getValue().getEndsAt(),
                        Comparator.nullsLast(Comparator.naturalOrder())));
        return items.stream().map(Map.Entry::getValue).collect(Collectors.toList());
    }

    /** Preloaded view for one activeCodes call: zero queries after construction. */
    private record PromoView(
            Map<UUID, List<PromoCampaignTranslation>> translations,
            Map<UUID, PromoCampaignPopup> popups,
            Map<UUID, List<PromoCampaignMedia>> media,
            Map<UUID, PromoPartner> partners) {

        PromoCampaignTranslation translation(UUID campaignId, String lang) {
            PromoCampaignTranslation az = null;
            for (PromoCampaignTranslation t : translations.getOrDefault(campaignId, List.of())) {
                if (t.getLocale().name().equals(lang)) return t;
                if (t.getLocale() == PromoLocale.az) az = t;
            }
            return az;
        }

        String bannerUrl(PromoCampaign c, String lang) {
            String url = pickMedia(media.getOrDefault(c.getId(), List.of()), lang, true);
            if (url == null) url = pickMedia(media.getOrDefault(c.getId(), List.of()), "az", true);
            if (url == null) {
                if (c.getLogoUrlOverride() != null) url = c.getLogoUrlOverride();
                else if (c.getPartnerId() != null && partners.get(c.getPartnerId()) != null) {
                    url = partners.get(c.getPartnerId()).getLogoUrl();
                }
            }
            return url;
        }

        String heroUrl(PromoCampaign c, String lang) {
            String url = pickMedia(media.getOrDefault(c.getId(), List.of()), lang, false);
            if (url == null) url = pickMedia(media.getOrDefault(c.getId(), List.of()), "az", false);
            return url;
        }

        MobilePromoPartnerResponse partner(PromoCampaign c) {
            if (c.getPartnerId() == null) return null;
            return partnerBlock(c, partners.get(c.getPartnerId()));
        }
    }

    private static String pickMedia(List<PromoCampaignMedia> media, String lang, boolean banner) {
        for (PromoCampaignMedia m : media) {
            if (m.getLocale().name().equals(lang)) {
                String url = banner ? m.getBannerImageUrl() : m.getHeroImageUrl();
                if (url != null && !url.isBlank()) return url;
            }
        }
        return null;
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

    private static String pickTextColor(String brandColor) {
        if (brandColor == null || !HEX_COLOR.matcher(brandColor).matches()) return "#FFFFFF";
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
}
