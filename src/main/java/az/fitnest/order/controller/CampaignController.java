package az.fitnest.order.controller;

import az.fitnest.order.dto.ActiveCampaignResponseDto;
import az.fitnest.order.dto.CampaignDetailDto;
import az.fitnest.order.dto.CampaignImpressionRequest;
import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.repository.SubscriptionRepository;
import az.fitnest.order.service.CampaignEligibilityService;
import az.fitnest.order.util.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping
@RequiredArgsConstructor
@Slf4j
public class CampaignController {

    private final CampaignEligibilityService campaignEligibilityService;
    private final SubscriptionRepository subscriptionRepository;

    @GetMapping("/api/v1/campaigns/active")
    public ResponseEntity<ActiveCampaignResponseDto> getActiveCampaign(
            @RequestParam(name = "context", defaultValue = "popup") String context,
            @RequestHeader(name = "Accept-Language", defaultValue = "az") String acceptLanguage) {
        Long userId = UserContext.getCurrentUserId();
        String lang = UserContext.getCurrentLanguage();
        ActiveCampaignResponseDto response = campaignEligibilityService.popup(userId, context, lang, null);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/v1/campaigns/{id}")
    public ResponseEntity<CampaignDetailDto> getCampaignDetail(
            @PathVariable("id") Long id,
            @RequestHeader(name = "Accept-Language", defaultValue = "az") String acceptLanguage) {
        String lang = UserContext.getCurrentLanguage();
        CampaignDetailDto detail = campaignEligibilityService.getCampaignDetail(id, lang);
        return ResponseEntity.ok(detail);
    }

    @PostMapping("/api/v1/campaigns/{id}/impressions")
    public ResponseEntity<Void> recordImpression(
            @PathVariable("id") Long campaignId,
            @RequestBody CampaignImpressionRequest request) {
        Long userId = UserContext.getCurrentUserId();
        String context = request != null && request.getContext() != null ? request.getContext() : "POPUP";
        campaignEligibilityService.recordImpression(userId, campaignId, context);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/v1/me/subscriptions/campaign-banner/dismiss")
    public ResponseEntity<Void> dismissCampaignBanner() {
        Long userId = UserContext.getCurrentUserId();
        List<Subscription> activeSubs = subscriptionRepository.findByUserIdAndStatusIn(userId, List.of("ACTIVE", "FROZEN"));
        LocalDateTime now = campaignEligibilityService.getBakuNow();
        for (Subscription sub : activeSubs) {
            sub.setCampaignBannerDismissedAt(now);
            subscriptionRepository.save(sub);
        }
        return ResponseEntity.ok().build();
    }
}
