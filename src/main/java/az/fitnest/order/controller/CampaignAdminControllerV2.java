package az.fitnest.order.controller;

import az.fitnest.order.dto.CampaignPushRequest;
import az.fitnest.order.dto.CampaignPushResponse;
import az.fitnest.order.service.CampaignV2Service;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin send for a live bonus campaign. Saving a campaign does not send a push. */
@RestController
@RequestMapping("/api/v2/admin/campaigns")
@RequiredArgsConstructor
public class CampaignAdminControllerV2 {

    private final CampaignV2Service campaignV2Service;

    @PostMapping("/{id}/push")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CampaignPushResponse> push(
            @PathVariable("id") Long id,
            @Valid @RequestBody CampaignPushRequest request) {
        return ResponseEntity.ok(new CampaignPushResponse(campaignV2Service.push(id, request)));
    }
}
