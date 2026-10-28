package az.fitnest.order.service;

import az.fitnest.order.model.entity.PlanCampaign;
import az.fitnest.order.repository.PlanCampaignRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** Data-only fallback for existing environments with Flyway disabled and Hibernate schema updates. */
@Component @RequiredArgsConstructor
public class PlanCampaignSeed implements ApplicationRunner {
    private final PlanCampaignRepository repository;
    @Override public void run(ApplicationArguments args) {
        if (repository.findByCode("BAKCELL_PLANS").isPresent()) return;
        PlanCampaign campaign = new PlanCampaign();
        campaign.setId(UUID.fromString("bacc3110-2026-4000-8000-000000000001"));
        campaign.setCode("BAKCELL_PLANS"); campaign.setName("Bakcell plan banneri");
        campaign.setEnabled(false); campaign.setPackageIds(new Long[0]);
        campaign.setDurationMonths(new Integer[]{3, 6, 12}); campaign.setUpdatedAt(OffsetDateTime.now());
        campaign.setContent(Map.of(
                "az", content("Bakcell-ə özəl kampaniya", "3, 6 və 12 aylıq planlarda xüsusi təkliflər", "Xüsusi təkliflər"),
                "en", content("Exclusive Bakcell campaign", "Special offers on 3, 6 and 12 month plans", "Special offers"),
                "ru", content("Специальная акция Bakcell", "Специальные предложения на планы 3, 6 и 12 месяцев", "Специальные предложения")));
        try { repository.saveAndFlush(campaign); }
        catch (DataIntegrityViolationException e) {
            // Another replica can insert the same seed first. Any other failure must surface.
            if (repository.findByCode("BAKCELL_PLANS").isEmpty()) throw e;
        }
    }
    private PlanCampaign.Content content(String heading, String subtitle, String offers) {
        return new PlanCampaign.Content(heading, subtitle, offers, null, null, null, null, heading);
    }
}
