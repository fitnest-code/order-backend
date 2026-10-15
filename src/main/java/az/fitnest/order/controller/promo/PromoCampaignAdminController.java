package az.fitnest.order.controller.promo;

import az.fitnest.order.dto.promo.*;
import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.service.promo.PromoCampaignService;
import az.fitnest.order.service.promo.PromoPartnerService;
import az.fitnest.order.util.UserContext;import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/v1/admin/promo-campaigns")
@Tag(name = "Promo Campaigns (Admin)", description = "Promo code campaign management (admin panel)")
public class PromoCampaignAdminController {

    private final PromoCampaignService campaignService;
    private final PromoPartnerService partnerService;
    private final az.fitnest.order.service.promo.PromoNumbersQueryService numbersQueryService;
    private final az.fitnest.order.service.promo.PromoAuditLogService auditLogService;
    private final az.fitnest.order.service.promo.PromoGrantService grantService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @GetMapping("/summary")
    @Operation(summary = "KPI summary for A1 campaign list")
    public ResponseEntity<AdminPromoCampaignSummaryResponse> getSummary() {
        return ResponseEntity.ok(campaignService.getSummary());
    }

    @GetMapping
    @Operation(summary = "List campaigns with filters")
    public ResponseEntity<Page<AdminPromoCampaignListItemResponse>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) List<String> statuses,
            @RequestParam(required = false) UUID partnerId,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(campaignService.list(search, statuses, partnerId, from, to, page, size));
    }

    @GetMapping("/code-availability")
    @Operation(summary = "Check code uniqueness")
    public ResponseEntity<AdminPromoCodeAvailabilityResponse> checkCode(@RequestParam String code) {
        return ResponseEntity.ok(campaignService.checkCodeAvailability(code));
    }

    @GetMapping("/_debug/schema")
    @Operation(summary = "TEMPORARY: flyway history + promo column types")
    public ResponseEntity<Map<String, Object>> debugSchema() {
        List<Map<String, Object>> history;
        try {
            history = jdbcTemplate.queryForList(
                    "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 15");
        } catch (Exception e) {
            history = List.of(Map.of("error", String.valueOf(e.getMessage())));
        }
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                "SELECT table_name, column_name, udt_name FROM information_schema.columns " +
                "WHERE table_name IN ('promo_campaign','promo_partner','promo_grant','promo_audience_number','promo_campaign_media','promo_campaign_popup','promo_audit_log') " +
                "AND column_name IN ('plan_ids','default_prefixes','allowed_prefixes','tier_codes','logo_on_dark_url','logo_on_dark_url_override','duration_sec','banner_image_url','hero_image_url') " +
                "ORDER BY table_name, column_name");
        return ResponseEntity.ok(Map.of("flyway", history, "columns", columns));
    }

    @PostMapping
    @Operation(summary = "Create draft campaign")
    public ResponseEntity<AdminPromoCampaignResponse> create(@Valid @RequestBody AdminPromoCampaignRequest request) {
        Long adminId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(campaignService.create(request, adminId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Campaign detail")
    public ResponseEntity<AdminPromoCampaignResponse> detail(@PathVariable UUID id) {
        return ResponseEntity.ok(campaignService.getDetail(id));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Partial update")
    public ResponseEntity<AdminPromoCampaignResponse> patch(@PathVariable UUID id, @RequestBody Map<String, Object> updates) {
        Long adminId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(campaignService.patch(id, updates, adminId));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete draft only")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        Long adminId = UserContext.getCurrentUserId();
        campaignService.delete(id, adminId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/duplicate")
    @Operation(summary = "Duplicate campaign to new draft")
    public ResponseEntity<AdminPromoCampaignResponse> duplicate(@PathVariable UUID id) {
        Long adminId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(campaignService.duplicate(id, adminId));
    }

    @PostMapping("/{id}/grants")
    @Operation(summary = "Manually create a grant (support/testing, audit-logged)")
    public ResponseEntity<Map<String, Object>> createGrant(@PathVariable UUID id,
                                                          @RequestBody Map<String, Object> body) {
        Long adminId = UserContext.getCurrentUserId();
        Object userIdRaw = body.get("userId");
        Long userId = userIdRaw instanceof Number n ? n.longValue()
                : (userIdRaw != null ? Long.parseLong(userIdRaw.toString()) : null);
        String phone = body.get("phone") != null ? body.get("phone").toString() : null;
        var grant = grantService.createGrantForAdmin(id, userId, phone);
        auditLogService.log(adminId, "GRANT_CREATE", "PROMO_GRANT", grant.getId(),
                null, "{\"campaignId\":\"" + id + "\",\"userId\":" + userId + "}", null, null);
        return ResponseEntity.ok(Map.of(
                "grantId", grant.getId(),
                "campaignId", grant.getCampaignId(),
                "userId", grant.getUserId(),
                "status", grant.getStatus().name(),
                "popupStatus", grant.getPopupStatus().name()));
    }

    @GetMapping("/{id}/publish-check")
    @Operation(summary = "Publish blockers")
    public ResponseEntity<AdminPromoCampaignPublishCheckResponse> publishCheck(@PathVariable UUID id) {
        return ResponseEntity.ok(campaignService.publishCheck(id));
    }

    @PostMapping("/{id}/publish")
    @Operation(summary = "Publish or schedule")
    public ResponseEntity<AdminPromoCampaignResponse> publish(@PathVariable UUID id,
                                                              @RequestParam(required = false, defaultValue = "false") Boolean schedule) {
        Long adminId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(campaignService.publish(id, schedule, adminId));
    }

    @PostMapping("/{id}/unschedule")
    @Operation(summary = "Back to draft")
    public ResponseEntity<AdminPromoCampaignResponse> unschedule(@PathVariable UUID id) {
        Long adminId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(campaignService.unschedule(id, adminId));
    }

    @PostMapping("/{id}/pause")
    @Operation(summary = "Pause campaign")
    public ResponseEntity<AdminPromoCampaignResponse> pause(@PathVariable UUID id) {
        Long adminId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(campaignService.pause(id, adminId));
    }

    @PostMapping("/{id}/resume")
    @Operation(summary = "Resume campaign")
    public ResponseEntity<AdminPromoCampaignResponse> resume(@PathVariable UUID id) {
        Long adminId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(campaignService.resume(id, adminId));
    }

    @PostMapping("/{id}/end")
    @Operation(summary = "End campaign")
    public ResponseEntity<AdminPromoCampaignResponse> end(@PathVariable UUID id) {
        Long adminId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(campaignService.end(id, adminId));
    }

    @GetMapping("/{id}/stats")
    @Operation(summary = "Campaign KPIs and funnel")
    public ResponseEntity<AdminPromoCampaignStatsResponse> stats(@PathVariable UUID id) {
        return ResponseEntity.ok(campaignService.getStats(id));
    }

    @GetMapping("/{id}/numbers")
    @Operation(summary = "Paginated numbers (masked; reveal needs PROMO_PII_VIEW)")
    public ResponseEntity<AdminPromoCampaignNumbersResponse> numbers(@PathVariable UUID id,
                                                                     @RequestParam(required = false) String phone,
                                                                     @RequestParam(required = false) String status,
                                                                     @RequestParam(defaultValue = "0") int page,
                                                                     @RequestParam(defaultValue = "50") int size,
                                                                     @RequestParam(defaultValue = "false") boolean reveal) {
        return ResponseEntity.ok(numbersQueryService.numbers(id, phone, status, page, size, reveal));
    }

    @GetMapping("/{id}/numbers/{phoneHash}/reveal")
    @Operation(summary = "Reveal full phone (PROMO_PII_VIEW, logged)")
    public ResponseEntity<Map<String, String>> revealNumber(@PathVariable UUID id,
                                                            @PathVariable String phoneHash) {
        return ResponseEntity.ok(Map.of("phone", numbersQueryService.revealPhone(id, phoneHash)));
    }

    @GetMapping("/{id}/export")
    @Operation(summary = "Export campaign CSV (masked phones unless PROMO_PII_VIEW)")
    public ResponseEntity<String> exportCsv(@PathVariable UUID id,
                                            @RequestParam(defaultValue = "false") boolean reveal) {
        if (reveal && !az.fitnest.order.util.UserContext.hasAuthority("PROMO_PII_VIEW")) {
            throw new az.fitnest.order.exception.ForbiddenException("error.forbidden");
        }
        List<az.fitnest.order.model.entity.promo.PromoGrant> grants = campaignService.exportGrants(id);
        StringBuilder sb = new StringBuilder("status,phone_hash,registered,saw_popup,redeemed_at\n");
        for (var g : grants) {
            sb.append(g.getStatus()).append(',')
                    .append(g.getPhoneHash()).append(',')
                    .append(true).append(',')
                    .append(g.getPopupStatus() == az.fitnest.order.model.entity.promo.PromoPopupStatus.SEEN).append(',')
                    .append(g.getRedeemedAt() != null ? g.getRedeemedAt().toString() : "")
                    .append('\n');
        }
        auditLogService.log(az.fitnest.order.util.UserContext.getCurrentUserId(), "EXPORT", "PROMO_CAMPAIGN", id,
                null, "{\"rows\":" + grants.size() + ",\"reveal\":" + reveal + "}", null, null);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=promo_campaign_export_" + id + ".csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(sb.toString());
    }

    @GetMapping("/{id}/export-json")
    @Operation(summary = "Export campaign numbers (masked by default)")
    public ResponseEntity<List<AdminPromoCampaignNumberItemResponse>> export(@PathVariable UUID id) {
        return ResponseEntity.ok(campaignService.exportNumbers(id));
    }
}
