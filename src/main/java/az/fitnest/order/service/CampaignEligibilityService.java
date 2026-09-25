package az.fitnest.order.service;

import az.fitnest.order.dto.*;
import az.fitnest.order.model.entity.*;
import az.fitnest.order.repository.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CampaignEligibilityService {

    private static final Logger log = LoggerFactory.getLogger(CampaignEligibilityService.class);
    private static final ZoneId BAKU_ZONE = ZoneId.of("Asia/Baku");

    private final CampaignRepository campaignRepository;
    private final CampaignOfferRepository campaignOfferRepository;
    private final CampaignTermRepository campaignTermRepository;
    private final UserCampaignRedemptionRepository userCampaignRedemptionRepository;
    private final CampaignImpressionRepository campaignImpressionRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final TranslationService translationService;

    public ZoneId getBakuZone() {
        return BAKU_ZONE;
    }

    public LocalDateTime getBakuNow() {
        return LocalDateTime.now(BAKU_ZONE);
    }

    @Transactional(readOnly = true)
    public BonusDecision resolve(Long userId, Long packageId, Integer optionDurationMonths, PurchaseKind purchaseKind, LocalDateTime now) {
        if (optionDurationMonths == null) {
            return BonusDecision.notApplied(0);
        }
        int paidMonths = optionDurationMonths;

        if (purchaseKind != PurchaseKind.NEW_PURCHASE) {
            return BonusDecision.notApplied(paidMonths);
        }

        LocalDateTime bakuNow = now != null ? now : getBakuNow();

        Optional<Campaign> activeCampaignOpt = campaignRepository
                .findFirstByStatusAndStartAtLessThanEqualAndEndAtGreaterThanEqual(CampaignStatus.ACTIVE, bakuNow, bakuNow);

        if (activeCampaignOpt.isEmpty()) {
            return BonusDecision.notApplied(paidMonths);
        }

        Campaign campaign = activeCampaignOpt.get();

        Optional<CampaignOffer> offerOpt = campaignOfferRepository
                .findByCampaignIdAndBaseDurationMonths(campaign.getId(), paidMonths);

        if (offerOpt.isEmpty()) {
            return BonusDecision.notApplied(paidMonths);
        }

        CampaignOffer offer = offerOpt.get();

        if (userId != null) {
            if (userCampaignRedemptionRepository.existsByUserIdAndCampaignId(userId, campaign.getId())) {
                return BonusDecision.notApplied(paidMonths);
            }

            if (subscriptionRepository.existsByUserIdAndStatusIn(userId, List.of("ACTIVE", "FROZEN"))) {
                return BonusDecision.notApplied(paidMonths);
            }
        }

        String offerLabel = getTranslatedValue("CAMPAIGNOFFER", offer.getId().toString(), "label", null, "AZ");

        return BonusDecision.builder()
                .applied(true)
                .campaignId(campaign.getId())
                .bonusMonths(offer.getBonusMonths())
                .paidMonths(paidMonths)
                .totalMonths(paidMonths + offer.getBonusMonths())
                .campaignLabel(offerLabel)
                .build();
    }

    @Transactional(readOnly = true)
    public ActiveCampaignResponseDto popup(Long userId, String context, String language, LocalDateTime now) {
        LocalDateTime bakuNow = now != null ? now : getBakuNow();

        Optional<Campaign> campaignOpt = campaignRepository
                .findFirstByStatusAndStartAtLessThanEqualAndEndAtGreaterThanEqual(CampaignStatus.ACTIVE, bakuNow, bakuNow);

        if (campaignOpt.isEmpty()) {
            return ActiveCampaignResponseDto.builder()
                    .eligible(false)
                    .showPopup(false)
                    .nextEligibleShowAt(null)
                    .campaign(null)
                    .build();
        }

        Campaign campaign = campaignOpt.get();

        if (userId != null && userCampaignRedemptionRepository.existsByUserIdAndCampaignId(userId, campaign.getId())) {
            return ActiveCampaignResponseDto.builder()
                    .eligible(false)
                    .showPopup(false)
                    .nextEligibleShowAt(null)
                    .campaign(null)
                    .build();
        }

        boolean isPopupContext = "popup".equalsIgnoreCase(context);
        boolean showPopup = true;
        LocalDateTime nextEligibleShowAt = null;

        if (isPopupContext && userId != null) {
            Optional<CampaignImpression> impressionOpt = campaignImpressionRepository
                    .findByUserIdAndCampaignIdAndContext(userId, campaign.getId(), "POPUP");

            if (impressionOpt.isPresent()) {
                LocalDateTime lastShown = impressionOpt.get().getLastShownAt();
                LocalDate todayBaku = bakuNow.toLocalDate();
                if (!lastShown.toLocalDate().isBefore(todayBaku)) {
                    showPopup = false;
                    nextEligibleShowAt = todayBaku.plusDays(1).atStartOfDay();
                }
            }
        }

        CampaignDetailDto detailDto = buildCampaignDetail(campaign, language);

        return ActiveCampaignResponseDto.builder()
                .eligible(true)
                .showPopup(showPopup)
                .nextEligibleShowAt(nextEligibleShowAt)
                .campaign(detailDto)
                .build();
    }

    @Transactional(readOnly = true)
    public CampaignDetailDto getCampaignDetail(Long id, String language) {
        Campaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found with id: " + id));
        return buildCampaignDetail(campaign, language);
    }

    @Transactional
    public void recordImpression(Long userId, Long campaignId, String context) {
        if (userId == null || campaignId == null || context == null) return;
        LocalDateTime bakuNow = getBakuNow();
        CampaignImpression impression = campaignImpressionRepository
                .findByUserIdAndCampaignIdAndContext(userId, campaignId, context.toUpperCase())
                .orElse(new CampaignImpression(null, userId, campaignId, context.toUpperCase(), bakuNow));
        impression.setLastShownAt(bakuNow);
        campaignImpressionRepository.save(impression);
    }

    public CampaignDetailDto buildCampaignDetail(Campaign campaign, String language) {
        String lang = language != null ? language : "AZ";
        String idStr = campaign.getId().toString();

        String title = getTranslatedValue("CAMPAIGN", idStr, "title", null, lang);
        String description = getTranslatedValue("CAMPAIGN", idStr, "description", null, lang);
        String ctaLabel = getTranslatedValue("CAMPAIGN", idStr, "ctaLabel", null, lang);

        List<CampaignOffer> offers = campaignOfferRepository.findByCampaignId(campaign.getId());
        List<CampaignOfferDto> offerDtos = offers.stream().map(o -> {
            String label = getTranslatedValue("CAMPAIGNOFFER", o.getId().toString(), "label", null, lang);
            return CampaignOfferDto.builder()
                    .baseDurationMonths(o.getBaseDurationMonths())
                    .bonusMonths(o.getBonusMonths())
                    .totalMonths(o.getBaseDurationMonths() + o.getBonusMonths())
                    .label(label)
                    .build();
        }).collect(Collectors.toList());

        List<CampaignTerm> terms = campaignTermRepository.findByCampaignIdOrderBySortOrderAsc(campaign.getId());
        List<CampaignTermDto> termDtos = terms.stream().map(t -> {
            String text = getTranslatedValue("CAMPAIGNTERM", t.getId().toString(), "text", null, lang);
            return CampaignTermDto.builder()
                    .text(text)
                    .isPositive(t.getIsPositive())
                    .sortOrder(t.getSortOrder())
                    .build();
        }).collect(Collectors.toList());

        return CampaignDetailDto.builder()
                .id(campaign.getId())
                .code(campaign.getCode())
                .title(title)
                .description(description)
                .bannerImageUrl(campaign.getBannerImageUrl())
                .ctaLabel(ctaLabel)
                .ctaTarget(campaign.getCtaTarget())
                .startAt(campaign.getStartAt())
                .endAt(campaign.getEndAt())
                .offers(offerDtos)
                .terms(termDtos)
                .build();
    }

    private String getTranslatedValue(String entityType, String entityId, String fieldName, String fallbackValue, String languageCode) {
        try {
            String val = translationService.getTranslatedValue(entityType, entityId, fieldName, languageCode);
            if (val != null && !val.trim().isEmpty()) {
                return val;
            }
        } catch (Exception e) {
            log.warn("Translation lookup failed for entityType={}, entityId={}, fieldName={}", entityType, entityId, fieldName);
        }
        return fallbackValue;
    }
}
