package az.fitnest.order.service.promo;

import az.fitnest.order.model.entity.promo.*;
import az.fitnest.order.repository.promo.*;
import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.exception.ConflictException;
import az.fitnest.order.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PromoGrantService {

    private final PromoGrantRepository grantRepository;
    private final PromoCampaignRepository campaignRepository;
    private final PromoPhoneHasher phoneHasher;
    private final PromoAudienceNumberRepository audienceNumberRepository;
    private final PromoEventPublisher eventPublisher;

    @Transactional
    public PromoGrant createGrant(UUID campaignId, Long userId, String phoneHash) {
        // BR-8: if a phone hash already has a REDEEMED grant on another account, do not create
        boolean redeemedElsewhere = grantRepository.findByCampaignIdAndPhoneHashAndStatus(campaignId, phoneHash, PromoGrantStatus.REDEEMED).isPresent();
        if (redeemedElsewhere) {
            return null;
        }
        if (grantRepository.findByCampaignIdAndUserId(campaignId, userId).isPresent()) {
            return null;
        }
        PromoGrant grant = new PromoGrant();
        grant.setCampaignId(campaignId);
        grant.setUserId(userId);
        grant.setPhoneHash(phoneHash);
        grant.setStatus(PromoGrantStatus.GRANTED);
        grant.setPopupStatus(PromoPopupStatus.PENDING);
        PromoGrant saved = grantRepository.save(grant);

        campaignRepository.findById(campaignId).ifPresent(c ->
                eventPublisher.grantCreated(campaignId, c.getCode(), c.getPartnerId(), userId, phoneHash));
        return saved;
    }

    @Transactional
    public void markPopupSeen(UUID grantId) {
        PromoGrant grant = grantRepository.findById(grantId).orElse(null);
        if (grant == null || grant.getPopupStatus() == PromoPopupStatus.SEEN) return;
        grant.setPopupStatus(PromoPopupStatus.SEEN);
        grant.setPopupSeenAt(OffsetDateTime.now());
        grantRepository.save(grant);
        campaignRepository.findById(grant.getCampaignId()).ifPresent(c ->
                eventPublisher.popupSeen(grant.getCampaignId(), c.getCode(), c.getPartnerId(),
                        grant.getUserId(), grant.getPhoneHash()));
    }

    @Transactional
    public PromoGrant reserve(UUID campaignId, Long userId, String orderId, OffsetDateTime until) {
        PromoGrant grant = grantRepository.findByCampaignIdAndUserIdWithLock(campaignId, userId)
                .orElseThrow(() -> new PromoReservationException("error.promo_not_eligible"));
        if (grant.getStatus() == PromoGrantStatus.REDEEMED) {
            throw new PromoReservationException("error.promo_already_used");
        }
        if (grant.getStatus() == PromoGrantStatus.RESERVED && grant.getReservedOrderId() != null
                && !grant.getReservedOrderId().equals(orderId)) {
            throw new PromoReservationException("error.promo_reserved");
        }
        if (grant.getStatus() != PromoGrantStatus.GRANTED && grant.getStatus() != PromoGrantStatus.RESERVED) {
            throw new PromoReservationException("error.promo_not_active");
        }
        grant.setStatus(PromoGrantStatus.RESERVED);
        grant.setReservedOrderId(orderId);
        grant.setReservedUntil(until);
        PromoGrant saved = grantRepository.save(grant);
        campaignRepository.findById(campaignId).ifPresent(c ->
                eventPublisher.grantReserved(campaignId, c.getCode(), c.getPartnerId(), userId, grant.getPhoneHash(), orderId));
        return saved;
    }

    /**
     * Shared usability gate used by quote and order paths alike (status window,
     * total limit, plan allowlist, grant state). Payment-method rules live with
     * the caller, which knows useFitCoin/paymentType.
     *
     * @return the usable grant
     * @throws BadRequestException with the API error code when unusable
     */
    @Transactional(readOnly = true)
    public PromoGrant assertGrantUsable(UUID campaignId, Long userId, String packageId) {
        PromoCampaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("error.promo_campaign_not_found"));
        if (campaign.getStatus() != PromoCampaignStatus.ACTIVE) {
            throw new BadRequestException("error.promo_not_active");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (campaign.getEndsAt().isBefore(now)) throw new BadRequestException("error.promo_expired");
        if (campaign.getStartsAt().isAfter(now)) throw new BadRequestException("error.promo_not_active");
        if (campaign.getTotalLimit() != null && campaign.getRedeemedCount() >= campaign.getTotalLimit()) {
            throw new BadRequestException("error.promo_limit_reached");
        }
        if (packageId != null && campaign.getPlanIds() != null && campaign.getPlanIds().length > 0
                && !Arrays.asList(campaign.getPlanIds()).contains(packageId)) {
            throw new BadRequestException("error.promo_plan_not_allowed");
        }
        PromoGrant grant = grantRepository.findByCampaignIdAndUserId(campaignId, userId)
                .orElseThrow(() -> new BadRequestException("error.promo_not_eligible"));
        if (grant.getStatus() == PromoGrantStatus.REDEEMED) {
            throw new BadRequestException("error.promo_already_used");
        }
        if (grant.getStatus() != PromoGrantStatus.GRANTED && grant.getStatus() != PromoGrantStatus.RESERVED) {
            throw new BadRequestException("error.promo_not_active");
        }
        return grant;
    }

    @Transactional
    public void release(UUID grantId) {
        grantRepository.findById(grantId).ifPresent(g -> {
            if (g.getStatus() == PromoGrantStatus.RESERVED) {
                g.setStatus(PromoGrantStatus.GRANTED);
                g.setReservedOrderId(null);
                g.setReservedUntil(null);
                grantRepository.save(g);
                campaignRepository.findById(g.getCampaignId()).ifPresent(c ->
                        eventPublisher.grantReleased(g.getCampaignId(), c.getCode(), c.getPartnerId(), g.getUserId(), g.getPhoneHash()));
            }
        });
    }

    @Transactional
    public void redeem(UUID grantId, String orderId, String paymentId,
                       BigDecimal baseAmount, BigDecimal discountAmount,
                       BigDecimal finalAmount) {
        PromoGrant grant = grantRepository.findByIdWithLock(grantId).orElse(null);
        if (grant == null) return;
        if (grant.getStatus() == PromoGrantStatus.REDEEMED) return;
        grant.setStatus(PromoGrantStatus.REDEEMED);
        grant.setRedeemedOrderId(orderId);
        grant.setPaymentId(paymentId);
        grant.setBaseAmount(baseAmount);
        grant.setDiscountAmount(discountAmount);
        grant.setFinalAmount(finalAmount);
        grant.setRedeemedAt(OffsetDateTime.now());
        grant.setReservedOrderId(null);
        grant.setReservedUntil(null);
        grantRepository.save(grant);

        // Atomic increment (no lost updates under concurrent redemptions, 13.3).
        // The total-limit gate is enforced at reserve/quote time; the scheduler
        // ends campaigns that reach the limit within the minute.
        campaignRepository.incrementRedeemedCount(grant.getCampaignId());
        PromoCampaign campaign = campaignRepository.findById(grant.getCampaignId()).orElse(null);
        if (campaign != null) {
            eventPublisher.grantRedeemed(grant.getCampaignId(), campaign.getCode(), campaign.getPartnerId(),
                    grant.getUserId(), grant.getPhoneHash(), orderId, discountAmount);
        }
    }

    @Transactional
    public void expireReservations() {
        int released = grantRepository.releaseExpiredReservations(OffsetDateTime.now());
        if (released > 0) {
            log.info("Expired {} promo reservations", released);
        }
    }

    /**
     * Admin manual grant (support/testing). For list campaigns a phone must be
     * given and be on the list; BR-8 (one account per number) still enforced.
     */
    @Transactional
    public PromoGrant createGrantForAdmin(UUID campaignId, Long userId, String phone) {
        PromoCampaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("error.promo_campaign_not_found"));
        if (userId == null) throw new BadRequestException("error.validation");
        String phoneHash;
        if (campaign.getAudienceType() == PromoAudienceType.UPLOADED_LIST) {
            if (phone == null || phone.isBlank()) throw new BadRequestException("error.promo_not_eligible");
            String normalized = phoneHasher.normalizePhone(phone);
            if (normalized == null) throw new BadRequestException("error.promo_not_eligible");
            phoneHash = phoneHasher.hashPhone(normalized);
            if (!audienceNumberRepository.existsByCampaignIdAndPhoneHash(campaignId, phoneHash)) {
                throw new BadRequestException("error.promo_not_eligible");
            }
        } else {
            String normalized = phone != null && !phone.isBlank() ? phoneHasher.normalizePhone(phone) : null;
            phoneHash = normalized != null ? phoneHasher.hashPhone(normalized) : "";
        }
        PromoGrant existing = grantRepository.findByCampaignIdAndUserId(campaignId, userId).orElse(null);
        if (existing != null) return existing;
        PromoGrant created = createGrant(campaignId, userId, phoneHash);
        if (created == null) {
            throw new ConflictException("error.promo_already_used");
        }
        return created;
    }
}
