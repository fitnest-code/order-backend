package az.fitnest.order.controller.promo;

import az.fitnest.order.dto.promo.AdminPromoAudienceUploadResponse;
import az.fitnest.order.dto.promo.AdminPromoAudienceUploadStatusResponse;
import az.fitnest.order.service.promo.PromoAudienceUploadService;
import az.fitnest.order.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/v1/admin/promo-campaigns/{campaignId}/audience")
@Tag(name = "Promo Audience (Admin)", description = "Audience uploads for promo campaigns")
public class PromoAudienceAdminController {

    private final PromoAudienceUploadService uploadService;

    @PostMapping(value = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload audience file")
    public ResponseEntity<AdminPromoAudienceUploadResponse> upload(
            @PathVariable UUID campaignId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mode", defaultValue = "REPLACE") String mode) {
        Long adminId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(uploadService.upload(campaignId, file, mode, adminId));
    }

    @GetMapping("/uploads")
    @Operation(summary = "Upload history")
    public ResponseEntity<List<AdminPromoAudienceUploadResponse>> history(@PathVariable UUID campaignId) {
        return ResponseEntity.ok(uploadService.history(campaignId));
    }

    @GetMapping("/uploads/{uploadId}")
    @Operation(summary = "Upload status + report")
    public ResponseEntity<AdminPromoAudienceUploadStatusResponse> status(@PathVariable UUID campaignId,
                                                                         @PathVariable UUID uploadId) {
        return ResponseEntity.ok(uploadService.uploadStatus(campaignId, uploadId));
    }

    @GetMapping("/uploads/{uploadId}/report.csv")
    @Operation(summary = "Download full problem report")
    public ResponseEntity<String> reportCsv(@PathVariable UUID campaignId, @PathVariable UUID uploadId) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=promo_audience_report_" + uploadId + ".csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(uploadService.uploadReportCsv(campaignId, uploadId));
    }

    @GetMapping("/template")
    @Operation(summary = "Download template")
    public ResponseEntity<String> template() {
        String csv = "phone\n0554123318\n994554123318\n55-123-45-67\n";
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=promo_audience_template.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }
}
