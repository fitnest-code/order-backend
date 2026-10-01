package az.fitnest.order.job;

import az.fitnest.order.model.entity.Campaign;
import az.fitnest.order.model.entity.CampaignStatus;
import az.fitnest.order.repository.CampaignRepository;
import az.fitnest.order.service.CampaignEligibilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class CampaignExpiryJob {

    private final CampaignRepository campaignRepository;
    private final CampaignEligibilityService campaignEligibilityService;

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void expireCampaigns() {
        LocalDateTime bakuNow = campaignEligibilityService.getBakuNow();
        List<Campaign> expired = campaignRepository.findByStatusAndEndAtBefore(CampaignStatus.ACTIVE, bakuNow);
        for (Campaign c : expired) {
            c.setStatus(CampaignStatus.EXPIRED);
            campaignRepository.save(c);
            log.info("Campaign ID {} ({}) expired at {}", c.getId(), c.getCode(), bakuNow);
        }
    }
}
