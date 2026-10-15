package az.fitnest.order.service.promo;

import az.fitnest.order.model.entity.promo.*;
import az.fitnest.order.repository.promo.*;
import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.exception.ConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
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
    public void createGrantsForNumbers(UUID campaignId, List<String> phoneHashes) {
        for (String hash : phoneHashes) {
            // Look up user by phone hash
            Long userId = findUserIdByPhoneHash(hash);
            if (userId != null) {
                try {
                    createGrant(campaignId, userId, hash);
                } catch (Exception e) {
                    log.warn("Failed creating grant for campaign={} hash={}", campaignId, hash, e);
                }
            }
        }
    }

    @Transactional
    public void markPopupSeen(UUID grantId) {
        grantRepository.findById(grantId).ifPresent(g -> {
            g.setPopupStatus(PromoPopupStatus.SEEN);
            g.setPopupSeenAt(OffsetDateTime.now());
            grantRepository.save(g);
            campaignRepository.findById(g.getCampaignId()).ifPresent(c ->
                    eventPublisher.popupSeen(g.getCampaignId(), c.getCode(), c.getPartnerId(), g.getUserId(), g.getPhoneHash()));
        });
    }

    @Transactional
    public PromoGrant reserve(UUID campaignId, Long userId, String orderId, OffsetDateTime until) {
        try {
            PromoGrant grant = grantRepository.findByCampaignIdAndUserIdWithLock(campaignId, userId)
                    .orElseThrow(() -> new IllegalStateException("GRANT_NOT_FOUND"));
            if (grant.getStatus() == PromoGrantStatus.REDEEMED) {
                throw new IllegalStateException("PROMO_ALREADY_USED");
            }
            if (grant.getStatus() == PromoGrantStatus.RESERVED && grant.getReservedOrderId() != null
                    && !grant.getReservedOrderId().equals(orderId)) {
                throw new IllegalStateException("PROMO_RESERVED");
            }
            if (grant.getStatus() != PromoGrantStatus.GRANTED && grant.getStatus() != PromoGrantStatus.RESERVED) {
                throw new IllegalStateException("PROMO_NOT_ACTIVE");
            }
            grant.setStatus(PromoGrantStatus.RESERVED);
            grant.setReservedOrderId(orderId);
            grant.setReservedUntil(until);
            PromoGrant saved = grantRepository.save(grant);
            campaignRepository.findById(campaignId).ifPresent(c ->
                    eventPublisher.grantReserved(campaignId, c.getCode(), c.getPartnerId(), userId, grant.getPhoneHash(), null));
            return saved;
        } finally {
            // lock released at transaction end
        }
    }

    private String phoneHashSupplier(PromoGrant grant) {
        return grant != null ? grant.getPhoneHash() : null;
    }

    @Transactional
    public void release(UUID grantId) {        grantRepository.findById(grantId).ifPresent(g -> {
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
                       java.math.BigDecimal baseAmount, java.math.BigDecimal discountAmount,
                       java.math.BigDecimal finalAmount) {
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
        OffsetDateTime now = OffsetDateTime.now();
        List<PromoGrant> expired = grantRepository.findByStatusAndReservedUntilBefore(PromoGrantStatus.RESERVED, now);
        for (PromoGrant g : expired) {
            g.setStatus(PromoGrantStatus.GRANTED);
            g.setReservedOrderId(null);
            g.setReservedUntil(null);
            grantRepository.save(g);
        }
        if (!expired.isEmpty()) {
            log.info("Expired {} promo reservations", expired.size());
        }
    }

    /**
     * Admin manual grant (support/testing). For list campaigns a phone must be
     * given and be on the list; BR-8 (one account per number) still enforced.
     */
    @Transactional
    public PromoGrant createGrantForAdmin(UUID campaignId, Long userId, String phone) {
        PromoCampaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.promo_campaign_not_found"));
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
            phoneHash = phone != null && !phone.isBlank()
                    ? phoneHasher.hashPhone(phoneHasher.normalizePhone(phone))
                    : "";
        }
        PromoGrant existing = grantRepository.findByCampaignIdAndUserId(campaignId, userId).orElse(null);
        if (existing != null) return existing;
        PromoGrant created = createGrant(campaignId, userId, phoneHash);
        if (created == null) {
            throw new ConflictException("error.promo_already_used");
        }
        return created;
    }

    private Long findUserIdByPhoneHash(String phoneHash) {
        // Look up via user-backend gRPC or local users table if available.
        // Identity service publishes user.phone.verified; on that event we create grants.
        return null;
    }
}
