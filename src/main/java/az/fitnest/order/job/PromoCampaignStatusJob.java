package az.fitnest.order.job;

import az.fitnest.order.model.entity.promo.PromoCampaign;
import az.fitnest.order.model.entity.promo.PromoCampaignStatus;
import az.fitnest.order.repository.promo.PromoCampaignRepository;
import az.fitnest.order.service.promo.PromoGrantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class PromoCampaignStatusJob {

    private final PromoCampaignRepository campaignRepository;
    private final PromoGrantService grantService;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Runs every minute:
     * - Scheduled -> Active at starts_at
     * - Active/Paused -> Ended at ends_at or when total_limit reached
     */
    @Scheduled(fixedDelayString = "${promo.scheduler.interval-ms:60000}")
    @Transactional
    public void tick() {
        OffsetDateTime now = OffsetDateTime.now();

        List<PromoCampaign> scheduled = campaignRepository.findScheduledToActivate(PromoCampaignStatus.SCHEDULED, now);
        for (PromoCampaign c : scheduled) {
            c.setStatus(PromoCampaignStatus.ACTIVE);
            campaignRepository.save(c);
            log.info("Promo campaign {} activated by scheduler", c.getId());
        }

        List<PromoCampaign> toEnd = campaignRepository.findCampaignsToEnd(
                List.of(PromoCampaignStatus.ACTIVE, PromoCampaignStatus.PAUSED), now);
        for (PromoCampaign c : toEnd) {
            c.setStatus(PromoCampaignStatus.ENDED);
            campaignRepository.save(c);
            log.info("Promo campaign {} ended by scheduler (ends_at reached)", c.getId());
        }

        List<PromoCampaign> atLimit = campaignRepository.findCampaignsAtLimit(
                List.of(PromoCampaignStatus.ACTIVE, PromoCampaignStatus.PAUSED));
        for (PromoCampaign c : atLimit) {
            c.setStatus(PromoCampaignStatus.ENDED);
            campaignRepository.save(c);
            log.info("Promo campaign {} ended by scheduler (total limit reached)", c.getId());
        }

        grantService.expireReservations();

        // NFR-3: reports contain masked phones; drop them after 7 days.
        int reportsCleared = jdbcTemplate.update(
                "UPDATE promo_audience_upload SET report_csv = NULL, report_url = NULL " +
                "WHERE uploaded_at < now() - INTERVAL '7 days' " +
                "AND (report_csv IS NOT NULL OR report_url IS NOT NULL)");
        if (reportsCleared > 0) log.info("Cleared {} stale promo upload reports", reportsCleared);

        // NFR-4: numbers are used only for their campaign; delete retention_days after end.
        int numbersDeleted = jdbcTemplate.update(
                "DELETE FROM promo_audience_number WHERE campaign_id IN (" +
                "SELECT c.id FROM promo_campaign c LEFT JOIN promo_partner p ON p.id = c.partner_id " +
                "WHERE c.status = 'ENDED' AND c.ends_at < now() - (COALESCE(p.retention_days, 90) || ' days')::interval)");
        if (numbersDeleted > 0) log.info("Deleted {} expired promo audience numbers", numbersDeleted);
    }
}
