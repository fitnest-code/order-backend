package az.fitnest.order.controller.promo;

import az.fitnest.order.dto.promo.AdminPromoPartnerRequest;
import az.fitnest.order.dto.promo.AdminPromoPartnerResponse;
import az.fitnest.order.service.promo.PromoPartnerService;
import az.fitnest.order.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/v1/admin/promo-partners")
@Tag(name = "Promo Partners (Admin)", description = "Promo partner management (admin panel)")
public class PromoPartnerAdminController {

    private final PromoPartnerService partnerService;

    @GetMapping
    @Operation(summary = "Partner list")
    public ResponseEntity<Page<AdminPromoPartnerResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(partnerService.getPartners(page, size));
    }

    @PostMapping
    @Operation(summary = "Create partner")
    public ResponseEntity<AdminPromoPartnerResponse> create(@Valid @RequestBody AdminPromoPartnerRequest request) {
        Long adminId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(partnerService.createPartner(request, adminId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Partner detail")
    public ResponseEntity<AdminPromoPartnerResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(partnerService.getPartner(id));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Edit partner")
    public ResponseEntity<AdminPromoPartnerResponse> update(@PathVariable UUID id,
                                                            @Valid @RequestBody AdminPromoPartnerRequest request) {
        Long adminId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(partnerService.updatePartner(id, request, adminId));
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate partner")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        Long adminId = UserContext.getCurrentUserId();
        partnerService.deactivatePartner(id, adminId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Activate partner")
    public ResponseEntity<Void> activate(@PathVariable UUID id) {
        Long adminId = UserContext.getCurrentUserId();
        partnerService.activatePartner(id, adminId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/stats")
    @Operation(summary = "Partner stats")
    public ResponseEntity<AdminPromoPartnerResponse> stats(@PathVariable UUID id) {
        return ResponseEntity.ok(partnerService.getPartnerStats(id));
    }

    @GetMapping("/{id}/report.csv")
    @Operation(summary = "Aggregated partner report (no phone numbers)")
    public ResponseEntity<String> reportCsv(@PathVariable UUID id) {
        String csv = "partner,active_campaigns,total_campaigns,eligible_numbers,redeemed\n"
                + partnerService.getPartnerStats(id).getName() + ","
                + partnerService.getPartnerStats(id).getActiveCampaigns() + ","
                + partnerService.getPartnerStats(id).getTotalCampaigns() + ","
                + partnerService.getPartnerStats(id).getEligibleNumbers() + ","
                + partnerService.getPartnerStats(id).getRedeemedCount() + "\n";
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=promo_partner_report_" + id + ".csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }
}
