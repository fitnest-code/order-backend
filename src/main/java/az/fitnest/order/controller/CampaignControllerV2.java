package az.fitnest.order.controller;

import az.fitnest.order.dto.CampaignDetailDto;
import az.fitnest.order.service.CampaignV2Service;
import az.fitnest.order.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Campaign detail for a push tap. Ended campaigns return 410 CAMPAIGN_NOT_AVAILABLE.
 * {@code GET /api/v1/campaigns/{id}} is unchanged and still returns those campaigns.
 */
@RestController
@RequestMapping("/api/v2/campaigns")
@RequiredArgsConstructor
public class CampaignControllerV2 {

    private final CampaignV2Service campaignV2Service;

    @GetMapping("/{id}")
    public ResponseEntity<CampaignDetailDto> getCampaignDetail(@PathVariable("id") Long id) {
        return ResponseEntity.ok(campaignV2Service.getAvailableDetail(id, UserContext.getCurrentLanguage()));
    }
}
