package az.fitnest.order.pricingcampaign.service;

import az.fitnest.order.model.entity.SubscriptionPackage;
import az.fitnest.order.model.entity.promo.PromoGrantStatus;
import az.fitnest.order.pricingcampaign.dto.*;
import az.fitnest.order.pricingcampaign.entity.PricingCampaign;
import az.fitnest.order.pricingcampaign.entity.PricingCampaignStatus;
import az.fitnest.order.pricingcampaign.repository.PricingCampaignRedemptionRepository;
import az.fitnest.order.pricingcampaign.repository.PricingCampaignRepository;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import az.fitnest.order.repository.SubscriptionRepository;
import az.fitnest.order.repository.promo.PromoCampaignRepository;
import az.fitnest.order.repository.promo.PromoGrantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PricingCampaignService {

    static final String BAKCELL20_CODE = "BAKCELL20";
    private static final List<String> ACTIVE_OR_FROZEN = List.of("ACTIVE", "FROZEN");
    // PENDING = a purchase already queued behind the running subscription.
    private static final List<String> QUEUED = List.of("PENDING");

    private final PricingCampaignRepository campaignRepository;
    private final PricingCampaignRedemptionRepository redemptionRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionPackageRepository packageRepository;
    private final PromoCampaignRepository promoCampaignRepository;
    private final PromoGrantRepository promoGrantRepository;

    @Transactional(readOnly = true)
    public Optional<PricingCampaign> findActiveCampaign() {
        OffsetDateTime now = OffsetDateTime.now();
        return campaignRepository.findByStatusAndType(PricingCampaignStatus.ACTIVE, PricingCampaign.TYPE_BLACK_FRIDAY)
                .stream()
                .filter(c -> PricingMath.isActiveAt(c, now))
                .max(Comparator.comparing(PricingCampaign::getStartsAt));
    }

    @Transactional(readOnly = true)
    public Optional<ActivePricingCampaignDto> findActiveForUser(Long userId) {
        return findActiveCampaign().map(c -> toDto(c, userId, hasActiveOrFrozenSub(userId)));
    }

    /** Campaign whose discount may actually be applied to this user right now (active, eligible, not yet used). */
    @Transactional(readOnly = true)
    public Optional<PricingCampaign> applicableCampaign(Long userId) {
        return findActiveCampaign().filter(c -> userId == null || canUse(c, userId));
    }

    @Transactional(readOnly = true)
    public PricingDto computePricing(BigDecimal listPrice, int months, Long userId) {
        return computePricing(listPrice, months, applicableCampaign(userId).orElse(null));
    }

    /** Overload for callers pricing many options: resolve the campaign once, then reuse it. */
    public PricingDto computePricing(BigDecimal listPrice, int months, PricingCampaign campaign) {
        BigDecimal percent = campaign == null ? BigDecimal.ZERO : PricingMath.percentFor(campaign.getDiscounts(), months);
        boolean applied = percent.signum() > 0;
        BigDecimal list = PricingMath.round2(listPrice);
        BigDecimal fin = applied ? PricingMath.finalPrice(list, percent) : list;
        return new PricingDto(list, fin, PricingMath.savedAmount(list, fin), percent,
                PricingMath.perMonth(fin, months), PricingMath.perMonth(list, months),
                applied, applied ? campaign.getId() : null);
    }

    /** Idempotent: a duplicate (campaign, user) is silently ignored by the DB. Refunds never reverse it. */
    @Transactional
    public void markRedeemed(Long userId, UUID campaignId, String orderId) {
        redemptionRepository.insertIgnoreDuplicate(campaignId, userId, orderId);
    }

    @Transactional(readOnly = true)
    public SubscriptionHomeStateDto homeState(Long userId) {
        Optional<PricingCampaign> campaign = findActiveCampaign();
        boolean hasSub = hasActiveOrFrozenSub(userId);
        ActivePricingCampaignDto dto = campaign.map(c -> toDto(c, userId, hasSub)).orElse(null);
        PricingCampaign pricingBasis = campaign.filter(c -> canUse(c, userId)).orElse(null);
        return new SubscriptionHomeStateDto(hasSub, dto != null && dto.showCountdown(), dto,
                startingFrom(pricingBasis), dto != null ? dto.nextPurchaseOffer() : null);
    }

    private ActivePricingCampaignDto toDto(PricingCampaign c, Long userId, boolean hasActiveOrFrozenSub) {
        boolean used = userId != null && usedByUser(c.getId(), userId);
        boolean eligible = userId == null || PricingMath.isEligible(hasBakcell20Grant(userId));
        boolean pending = userId != null && subscriptionRepository.existsByUserIdAndStatusIn(userId, QUEUED);
        List<PricingDiscountDto> tiers = c.getDiscounts().stream()
                .map(d -> new PricingDiscountDto(d.getMonths(), d.getPercent())).toList();
        NextPurchaseOfferDto offer = new NextPurchaseOfferDto(
                PricingMath.nextPurchaseAvailable(true, hasActiveOrFrozenSub, pending, used), c.getId(), tiers);
        return new ActivePricingCampaignDto(c.getId(), c.getCode(), c.getType(), c.getStartsAt(), c.getEndsAt(),
                c.getMaxPercent(), tiers, eligible, used, PricingMath.showCountdown(true, hasActiveOrFrozenSub), offer);
    }

    private boolean canUse(PricingCampaign c, Long userId) {
        return PricingMath.isEligible(hasBakcell20Grant(userId)) && !usedByUser(c.getId(), userId);
    }

    private boolean hasActiveOrFrozenSub(Long userId) {
        return userId != null && subscriptionRepository.existsByUserIdAndStatusIn(userId, ACTIVE_OR_FROZEN);
    }

    private boolean usedByUser(UUID campaignId, Long userId) {
        return redemptionRepository.existsByCampaignIdAndUserId(campaignId, userId);
    }

    /**
     * An un-redeemed, un-revoked BAKCELL20 grant means the user is on the partner deal. Read-only use of the
     * existing promo tables; the promo module itself is untouched.
     */
    boolean hasBakcell20Grant(Long userId) {
        return promoCampaignRepository.findByCodeNormalized(BAKCELL20_CODE)
                .flatMap(pc -> promoGrantRepository.findByCampaignIdAndUserId(pc.getId(), userId))
                .map(g -> g.getStatus() == PromoGrantStatus.GRANTED || g.getStatus() == PromoGrantStatus.RESERVED)
                .orElse(false);
    }

    private StartingFromDto startingFrom(PricingCampaign campaign) {
        record Candidate(PricingDto pricing, int months, String currency) {}
        return packageRepository.findByIsActiveTrueWithOptions().stream()
                .flatMap(p -> p.getOptions().stream()
                        .filter(o -> !Boolean.FALSE.equals(o.getIsActive()) && o.getPriceStandard() != null
                                && o.getDurationMonths() != null && o.getDurationMonths() > 0)
                        .map(o -> new Candidate(computePricing(o.getPriceStandard(), o.getDurationMonths(), campaign),
                                o.getDurationMonths(), currencyOf(p))))
                .min(Comparator.comparing(c -> c.pricing().monthlyPrice()))
                .map(c -> new StartingFromDto(c.pricing().monthlyPrice(), c.pricing().monthlyListPrice(),
                        c.months(), c.currency()))
                .orElse(null);
    }

    private static String currencyOf(SubscriptionPackage p) {
        return p.getCurrency() != null ? p.getCurrency() : "AZN";
    }
}
