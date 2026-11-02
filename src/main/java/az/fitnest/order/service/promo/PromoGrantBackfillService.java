package az.fitnest.order.service.promo;

import az.fitnest.order.client.PlanCampaignDirectoryClient;
import az.fitnest.order.exception.ResourceNotFoundException;
import az.fitnest.order.model.entity.promo.PromoAudienceType;
import az.fitnest.order.model.entity.promo.PromoCampaign;
import az.fitnest.order.model.entity.promo.PromoGrant;
import az.fitnest.order.repository.promo.PromoCampaignRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Creates grants for users that already existed when a prefix-based campaign
 * became active. New registrations are handled by {@code UserPhoneVerifiedListener};
 * this service covers the "include existing users" part of {@code ALL_USERS}.
 *
 * <p>Runs outside a single database transaction: each grant is created through
 * {@link PromoGrantService#createGrant}, which is idempotent per user and
 * enforces BR-8. A large audience therefore never holds one long transaction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PromoGrantBackfillService {

    static final int PAGE_SIZE = 200;

    private final PromoCampaignRepository campaignRepository;
    private final PlanCampaignDirectoryClient directoryClient;
    private final PromoGrantService grantService;
    private final PromoPhoneHasher phoneHasher;

    /**
     * Fire-and-forget hook used by publish/resume/scheduler. Failures are logged
     * and never break the campaign state transition.
     */
    @Async("promoUploadExecutor")
    public void backfillAsync(UUID campaignId) {
        try {
            BackfillResult result = backfill(campaignId);
            log.info("Promo grant backfill finished for campaign {}: {}", campaignId, result);
        } catch (Exception e) {
            log.error("Promo grant backfill failed for campaign {}: {}", campaignId, e.getMessage(), e);
        }
    }

    /**
     * Synchronous backfill (also used by the admin support endpoint). Returns the
     * number of users scanned / granted / skipped.
     */
    public BackfillResult backfill(UUID campaignId) {
        PromoCampaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("error.promo_campaign_not_found"));

        if (campaign.getAudienceType() != PromoAudienceType.ALL_USERS) {
            return new BackfillResult(campaignId, "skipped",
                    "only ALL_USERS campaigns are backfilled", 0, 0, 0);
        }
        if (Boolean.FALSE.equals(campaign.getIncludeExistingUsers())) {
            return new BackfillResult(campaignId, "skipped",
                    "include_existing_users is false", 0, 0, 0);
        }
        String[] allowedPrefixes = campaign.getAllowedPrefixes();
        if (allowedPrefixes == null || allowedPrefixes.length == 0) {
            return new BackfillResult(campaignId, "skipped",
                    "no allowed prefixes configured", 0, 0, 0);
        }

        long scanned = 0;
        long created = 0;
        long skipped = 0;

        for (String rawPrefix : allowedPrefixes) {
            String prefix = normalizePrefix(rawPrefix);
            if (prefix == null) {
                log.warn("Skipping invalid promo prefix '{}' on campaign {}", rawPrefix, campaignId);
                continue;
            }

            int page = 0;
            while (true) {
                PlanCampaignDirectoryClient.DirectoryUserPage pageResult =
                        directoryClient.listUsersByPrefix(prefix, page, PAGE_SIZE);
                List<PlanCampaignDirectoryClient.DirectoryUser> users =
                        pageResult.users() != null ? pageResult.users() : List.of();
                if (users.isEmpty()) {
                    break;
                }

                for (PlanCampaignDirectoryClient.DirectoryUser user : users) {
                    scanned++;
                    String normalized = user.mobile() != null
                            ? phoneHasher.normalizePhone(user.mobile())
                            : null;
                    if (user.userId() == null || normalized == null
                            || !phoneHasher.matchesAllowedPrefix(normalized, allowedPrefixes)) {
                        skipped++;
                        continue;
                    }

                    PromoGrant grant = grantService.createGrant(
                            campaignId, user.userId(), phoneHasher.hashPhone(normalized));
                    if (grant != null) {
                        created++;
                    } else {
                        skipped++;
                    }
                }

                if (!pageResult.hasNext()) {
                    break;
                }
                page++;
            }
        }

        log.info("Promo grant backfill for campaign {}: scanned={} created={} skipped={}",
                campaignId, scanned, created, skipped);
        return new BackfillResult(campaignId, "completed", null, scanned, created, skipped);
    }

    private static String normalizePrefix(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("\\D", "");
        if (digits.startsWith("994") && digits.length() > 3) {
            digits = digits.substring(3);
        } else if (digits.startsWith("0") && digits.length() > 1) {
            digits = digits.substring(1);
        }
        return digits.matches("\\d{2}") ? digits : null;
    }

    public record BackfillResult(UUID campaignId, String status, String reason,
                                 long scanned, long created, long skipped) {
    }
}
