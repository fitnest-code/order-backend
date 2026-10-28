package az.fitnest.order.controller;

import az.fitnest.order.service.PlanCampaignService;
import az.fitnest.order.service.PlanCampaignImageService;
import az.fitnest.order.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v2/me/plan-campaigns")
public class PlanCampaignController {
    private final PlanCampaignService service;
    private final PlanCampaignImageService images;
    @GetMapping("/images/{fsId}")
    public ResponseEntity<byte[]> image(@PathVariable String fsId) { return images.read(fsId); }

    @GetMapping
    public ResponseEntity<PlanCampaignService.MobileResponse> get(
            @RequestParam(required = false) Long packageId,
            @RequestHeader(value = "Accept-Language", defaultValue = "az") String language) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).varyBy("Authorization", "Accept-Language")
                .body(service.forUser(UserContext.getCurrentUserId(), packageId, language, OffsetDateTime.now()));
    }
}
