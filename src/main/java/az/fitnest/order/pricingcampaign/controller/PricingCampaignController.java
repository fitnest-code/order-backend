package az.fitnest.order.pricingcampaign.controller;

import az.fitnest.order.pricingcampaign.dto.ActivePricingCampaignDto;
import az.fitnest.order.pricingcampaign.dto.CampaignLandingDto;
import az.fitnest.order.pricingcampaign.service.PricingCampaignLandingService;
import az.fitnest.order.pricingcampaign.service.PricingCampaignService;
import az.fitnest.order.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Black Friday Campaign (Mobile)", description = "Active Black Friday pricing campaign and landing screen")
@RestController
@RequestMapping("/api/v2/campaigns/active")
@RequiredArgsConstructor
public class PricingCampaignController {

    private final PricingCampaignService service;
    private final PricingCampaignLandingService landingService;

    /** 200 with an empty body when nothing is running (outside the window or disabled). */
    @Operation(summary = "Active Black Friday campaign",
            description = "HTTP 200 with null body when no campaign is running.")
    @GetMapping
    public ResponseEntity<ActivePricingCampaignDto> getActive() {
        return ResponseEntity.ok(service.findActiveForUser(UserContext.getCurrentUserId()).orElse(null));
    }

    @Operation(summary = "Black Friday landing screen",
            description = "Ready-to-bind intro screen for the active Black Friday campaign: localized header, "
                    + "discount rows (3/6/12) each listing all packages with BF prices, terms and CTA. "
                    + "Locale from ?lang / Accept-Language (az|en|ru, fallback az). Same active-campaign "
                    + "resolution as /active: HTTP 200 with null body when no campaign is running. "
                    + "Not cached (Cache-Control: no-store). Checkout still uses preview + POST /api/v3/checkout.")
    @ApiResponse(responseCode = "200", description = "Landing payload, or empty body when no campaign is active",
            content = @Content(schema = @Schema(implementation = CampaignLandingDto.class)))
    @GetMapping("/landing")
    public ResponseEntity<CampaignLandingDto> getLanding() {
        // no-store: the payload embeds per-user flags and a time-boxed campaign window.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(landingService.findActiveLanding(UserContext.getCurrentUserId(),
                        UserContext.getCurrentLanguage()).orElse(null));
    }
}
