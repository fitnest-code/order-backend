package az.fitnest.order.service.promo;

import az.fitnest.order.dto.promo.AdminPromoAudienceProblemRow;
import az.fitnest.order.dto.promo.AdminPromoAudienceUploadResponse;
import az.fitnest.order.dto.promo.AdminPromoAudienceUploadStatusResponse;
import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.exception.ResourceNotFoundException;
import az.fitnest.order.model.entity.promo.*;
import az.fitnest.order.repository.promo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@Slf4j
public class PromoAudienceUploadService {

    private final PromoAudienceUploadRepository uploadRepository;
    private final PromoCampaignRepository campaignRepository;
    private final PromoAuditLogService auditLogService;
    private final PromoAudienceProcessor processor;

    @Transactional
    public AdminPromoAudienceUploadResponse upload(UUID campaignId, MultipartFile file, String modeStr, Long adminId) {
        PromoCampaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("error.promo_campaign_not_found"));

        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "";
        String lower = filename.toLowerCase();
        if (!lower.endsWith(".xlsx") && !lower.endsWith(".csv")) {
            throw new BadRequestException("error.upload_bad_format");
        }
        if (file.getSize() > PromoAudienceProcessor.MAX_FILE_SIZE) {
            throw new BadRequestException("error.upload_too_large");
        }
        PromoUploadMode mode;
        try {
            mode = modeStr != null ? PromoUploadMode.valueOf(modeStr.toUpperCase()) : PromoUploadMode.REPLACE;
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("error.upload_bad_format");
        }

        // Status matrix: Replace on Draft/Scheduled only; Append anywhere except Ended.
        if (mode == PromoUploadMode.REPLACE
                && campaign.getStatus() != PromoCampaignStatus.DRAFT
                && campaign.getStatus() != PromoCampaignStatus.SCHEDULED) {
            throw new BadRequestException("error.promo_invalid_status_transition");
        }
        if (campaign.getStatus() == PromoCampaignStatus.ENDED) {
            throw new BadRequestException("error.promo_invalid_status_transition");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new BadRequestException("error.upload_read_failed");
        }
        if (bytes.length > PromoAudienceProcessor.MAX_FILE_SIZE) {
            throw new BadRequestException("error.upload_too_large");
        }
        if (bytes.length == 0) {
            throw new BadRequestException("error.upload_no_phone_column");
        }

        PromoAudienceUpload upload = new PromoAudienceUpload();
        upload.setCampaignId(campaignId);
        upload.setFileName(filename);
        upload.setMode(mode);
        upload.setUploadedBy(adminId);
        upload.setStatus(PromoUploadStatus.PROCESSING);
        PromoAudienceUpload saved = uploadRepository.save(upload);

        // Async per spec (NFR-6): 200k rows <2 min; client polls status every 2s.
        // The original file is never persisted (NFR-3); only bytes in memory for the worker.
        // MUST run after commit: the worker reads the row in its own transaction,
        // and an early start would find nothing and exit silently.
        boolean isXlsx = lower.endsWith(".xlsx");
        UUID uploadId = saved.getId();
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        processor.processAsync(uploadId, bytes, isXlsx);
                    }
                });
        auditLogService.log(adminId, "UPLOAD", "PROMO_AUDIENCE", campaignId,
                null, Map.of("file", filename, "mode", mode.name()), null, null);
        return map(saved);
    }

    @Transactional(readOnly = true)
    public List<AdminPromoAudienceUploadResponse> history(UUID campaignId) {
        return uploadRepository.findByCampaignIdOrderByUploadedAtDesc(campaignId).stream()
                .map(this::map).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AdminPromoAudienceUploadStatusResponse uploadStatus(UUID campaignId, UUID uploadId) {
        PromoAudienceUpload upload = uploadRepository.findByIdAndCampaignId(uploadId, campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("error.promo_upload_not_found"));
        return AdminPromoAudienceUploadStatusResponse.builder()
                .id(upload.getId())
                .status(upload.getStatus().name())
                .totalRows(upload.getTotalRows())
                .valid(upload.getValid())
                .duplicates(upload.getDuplicates())
                .invalid(upload.getInvalid())
                .existingUsers(upload.getExistingUsers())
                .reportUrl(upload.getReportUrl())
                .problemRows(previewProblemRows(upload.getReportCsv()))
                .build();
    }

    /** First 50 problem rows for the admin preview table (spec A3). */
    private List<AdminPromoAudienceProblemRow> previewProblemRows(String reportCsv) {
        List<AdminPromoAudienceProblemRow> rows = new ArrayList<>();
        if (reportCsv == null || reportCsv.isBlank()) return rows;
        String[] lines = reportCsv.split("\n");
        for (int i = 1; i < lines.length && rows.size() < 50; i++) {
            String[] cols = lines[i].split(",", 3);
            if (cols.length < 3) continue;
            try {
                rows.add(AdminPromoAudienceProblemRow.builder()
                        .rowNumber(Integer.parseInt(cols[0].trim()))
                        .phone(cols[1].trim())
                        .issue(cols[2].trim())
                        .build());
            } catch (NumberFormatException ignored) {
            }
        }
        return rows;
    }

    private AdminPromoAudienceUploadResponse map(PromoAudienceUpload u) {
        return AdminPromoAudienceUploadResponse.builder()
                .id(u.getId())
                .campaignId(u.getCampaignId())
                .fileName(u.getFileName())
                .mode(u.getMode().name())
                .totalRows(u.getTotalRows())
                .valid(u.getValid())
                .duplicates(u.getDuplicates())
                .invalid(u.getInvalid())
                .existingUsers(u.getExistingUsers())
                .status(u.getStatus().name())
                .reportUrl(u.getReportUrl())
                .uploadedBy(u.getUploadedBy())
                .uploadedAt(u.getUploadedAt())
                .build();
    }

    @Transactional(readOnly = true)
    public String uploadReportCsv(UUID campaignId, UUID uploadId) {
        PromoAudienceUpload upload = uploadRepository.findByIdAndCampaignId(uploadId, campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("error.promo_upload_not_found"));
        return upload.getReportCsv() != null && !upload.getReportCsv().isBlank()
                ? upload.getReportCsv()
                : "row,phone,issue\n";
    }

}
