package az.fitnest.order.pricingcampaign.controller;

import az.fitnest.order.pricingcampaign.dto.ActivePricingCampaignDto;
import az.fitnest.order.pricingcampaign.service.PricingCampaignService;
import az.fitnest.order.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/campaigns/active")
@RequiredArgsConstructor
public class PricingCampaignController {

    private final PricingCampaignService service;

    /** 200 with an empty body when nothing is running (outside the window or disabled). */
    @GetMapping
    public ResponseEntity<ActivePricingCampaignDto> getActive() {
        return ResponseEntity.ok(service.findActiveForUser(UserContext.getCurrentUserId()).orElse(null));
    }
}
