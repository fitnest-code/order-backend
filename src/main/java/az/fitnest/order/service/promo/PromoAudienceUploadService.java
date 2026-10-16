package az.fitnest.order.service.promo;

import az.fitnest.order.dto.promo.AdminPromoAudienceUploadResponse;
import az.fitnest.order.dto.promo.AdminPromoAudienceUploadStatusResponse;
import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.exception.ResourceNotFoundException;
import az.fitnest.order.model.entity.promo.*;
import az.fitnest.order.repository.promo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PromoAudienceUploadService {

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024; // 10 MB
    private static final int MAX_ROWS = 200_000;
    private static final int INSERT_BATCH = 1_000;

    private final PromoAudienceUploadRepository uploadRepository;
    private final PromoAudienceNumberRepository numberRepository;
    private final PromoCampaignRepository campaignRepository;
    private final PromoPhoneHasher phoneHasher;
    private final PromoPhoneEncryption phoneEncryption;
    private final PromoGrantService grantService;
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
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BadRequestException("error.upload_too_large");
        }
        PromoUploadMode mode = modeStr != null ? PromoUploadMode.valueOf(modeStr.toUpperCase()) : PromoUploadMode.REPLACE;

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

        PromoAudienceUpload upload = new PromoAudienceUpload();
        upload.setCampaignId(campaignId);
        upload.setFileName(filename);
        upload.setMode(mode);
        upload.setUploadedBy(adminId);
        upload.setStatus(PromoUploadStatus.PROCESSING);
        upload = uploadRepository.save(upload);

        // Async per spec (NFR-6): 200k rows <2 min; client polls status every 2s.
        // The original file is never persisted (NFR-3); only bytes in memory for the worker.
        // MUST run after commit: the worker reads the row in its own transaction,
        // and an early start would find nothing and exit silently.
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        processor.processAsync(upload.getId(), bytes, lower.endsWith(".xlsx"));
                    }
                });
        auditLogService.log(adminId, "UPLOAD", "PROMO_AUDIENCE", campaignId,
                null, "{\"file\":\"" + filename.replace("\"", "") + "\",\"mode\":\"" + mode + "\"}", null, null);
        return map(upload);
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
                .problemRows(List.of())
                .build();
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

    /**
     * Async worker (separate bean so Spring @Async proxy applies).
     * Complexity: O(n) single pass + O(1) amortized set lookups; one SELECT to
     * preload existing hashes instead of O(n) EXISTS queries (13.2).
     */
    @Service
    @RequiredArgsConstructor
    @Slf4j
    public static class PromoAudienceProcessor {

        private final PromoAudienceUploadRepository uploadRepository;
        private final PromoAudienceNumberRepository numberRepository;
        private final PromoCampaignRepository campaignRepository;
        private final PromoPhoneHasher phoneHasher;
        private final PromoPhoneEncryption phoneEncryption;
        private final PromoGrantService grantService;

        @Async
        @Transactional
        public void processAsync(UUID uploadId, byte[] bytes, boolean xlsx) {
            PromoAudienceUpload upload = uploadRepository.findById(uploadId).orElse(null);
            if (upload == null) return;
            try {
                PromoCampaign campaign = campaignRepository.findById(upload.getCampaignId()).orElse(null);
                if (campaign == null) throw new IllegalStateException("campaign gone");
                processFile(campaign, upload, bytes, xlsx);
                upload.setStatus(PromoUploadStatus.DONE);
            } catch (Exception e) {
                log.error("Failed processing upload {}", uploadId, e);
                upload.setStatus(PromoUploadStatus.FAILED);
            }
            uploadRepository.save(upload);
        }

        private void processFile(PromoCampaign campaign, PromoAudienceUpload upload, byte[] bytes, boolean xlsx) throws Exception {
            List<String> rawPhones = xlsx ? readXlsx(bytes) : readCsv(bytes);

            String[] allowedPrefixes = campaign.getAllowedPrefixes() != null ? campaign.getAllowedPrefixes() : new String[0];

            // Preload existing hashes once: O(m) memory, 1 query (not O(n) EXISTS).
            // REPLACE starts from an empty list, so nothing is falsely flagged duplicate.
            Set<String> existing = upload.getMode() == PromoUploadMode.REPLACE
                    ? new HashSet<>()
                    : new HashSet<>(numberRepository.findPhoneHashesByCampaignId(campaign.getId()));

            List<String> problemRows = new ArrayList<>();
            Set<String> seenInFile = new HashSet<>();
            List<PromoAudienceNumber> batch = new ArrayList<>(INSERT_BATCH);
            int duplicates = 0, invalid = 0, total = 0, valid = 0;

            for (String raw : rawPhones) {
                if (total >= MAX_ROWS) break;
                total++;
                String normalized = phoneHasher.normalizePhone(raw);
                if (normalized == null) {
                    invalid++;
                    problemRows.add(total + "," + safe(raw) + ",INVALID_FORMAT");
                    continue;
                }
                if (!phoneHasher.isValidAzerbaijanMobile(normalized, allowedPrefixes)) {
                    invalid++;
                    problemRows.add(total + "," + safe(raw) + ",INVALID_PREFIX");
                    continue;
                }
                String hash = phoneHasher.hashPhone(normalized);
                if (!seenInFile.add(hash)) {
                    duplicates++;
                    problemRows.add(total + "," + mask(normalized) + ",DUPLICATE_IN_FILE");
                    continue;
                }
                if (existing.contains(hash)) {
                    duplicates++;
                    problemRows.add(total + "," + mask(normalized) + ",DUPLICATE_IN_CAMPAIGN");
                    continue;
                }
                existing.add(hash);
                PromoAudienceNumber n = new PromoAudienceNumber();
                n.setCampaignId(campaign.getId());
                n.setPhoneHash(hash);
                n.setPhoneEncrypted(phoneEncryption.encrypt(normalized));
                n.setUploadId(upload.getId());
                batch.add(n);
                valid++;
                if (batch.size() >= INSERT_BATCH) {
                    numberRepository.saveAll(batch);
                    batch.clear();
                }
            }

            upload.setTotalRows(total);
            upload.setValid(valid);
            upload.setDuplicates(duplicates);
            upload.setInvalid(invalid);

            if (upload.getMode() == PromoUploadMode.REPLACE) {
                // Delete numbers from previous uploads, keep just-inserted batch strategy simple:
                // delete-all then re-insert would lose data on failure, so instead delete
                // rows whose upload_id differs from this one.
                numberRepository.deleteByCampaignIdExcludingUpload(campaign.getId(), upload.getId());
            }

            if (!batch.isEmpty()) {
                numberRepository.saveAll(batch);
            }

            upload.setExistingUsers(0);
            if (!problemRows.isEmpty()) {
                StringBuilder csv = new StringBuilder("row,phone,issue\n");
                for (String p : problemRows) csv.append(p).append("\n");
                upload.setReportCsv(csv.toString());
            }

            if (upload.getMode() == PromoUploadMode.APPEND && campaign.getStatus() == PromoCampaignStatus.ACTIVE) {
                grantService.createGrantsForNumbers(campaign.getId(),
                        numberRepository.findPhoneHashesByCampaignId(campaign.getId()));
            }
        }

        private List<String> readCsv(byte[] bytes) throws Exception {
            List<String> out = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
                String line;
                boolean first = true;
                int phoneColumn = 0;
                while ((line = reader.readLine()) != null) {
                    if (out.size() + 1 > MAX_ROWS && !first) break;
                    if (first) {
                        first = false;
                        phoneColumn = detectPhoneColumn(line);
                        if (phoneColumn < 0) phoneColumn = 0;
                        continue;
                    }
                    String[] cols = splitCsv(line);
                    out.add(phoneColumn < cols.length ? cols[phoneColumn] : "");
                }
            }
            if (out.isEmpty()) throw new BadRequestException("error.upload_no_phone_column");
            return out;
        }

        private List<String> readXlsx(byte[] bytes) throws Exception {
            List<String> out = new ArrayList<>();
            try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                Sheet sheet = wb.getSheetAt(0); // first sheet per spec
                if (sheet == null) throw new BadRequestException("error.upload_no_phone_column");
                boolean first = true;
                int phoneColumn = 0;
                DataFormatter fmt = new DataFormatter();
                for (Row row : sheet) {
                    if (row == null) continue;
                    if (first) {
                        first = false;
                        phoneColumn = detectPhoneColumnXlsx(row, fmt);
                        if (phoneColumn < 0) phoneColumn = 0;
                        continue;
                    }
                    if (out.size() >= MAX_ROWS) break;
                    Cell cell = row.getCell(phoneColumn);
                    out.add(cell == null ? "" : fmt.formatCellValue(cell).trim());
                }
            }
            if (out.isEmpty()) throw new BadRequestException("error.upload_no_phone_column");
            return out;
        }

        private int detectPhoneColumn(String headerLine) {
            String[] headers = splitCsv(headerLine);
            for (int i = 0; i < headers.length; i++) {
                if (isPhoneHeader(headers[i])) return i;
            }
            return -1;
        }

        private int detectPhoneColumnXlsx(Row header, DataFormatter fmt) {
            int last = header.getLastCellNum();
            for (int i = 0; i < last; i++) {
                Cell c = header.getCell(i);
                if (c != null && isPhoneHeader(fmt.formatCellValue(c))) return i;
            }
            return -1;
        }

        private boolean isPhoneHeader(String h) {
            if (h == null) return false;
            String v = h.trim().toLowerCase();
            return v.equals("phone") || v.equals("msisdn") || v.equals("nomre") || v.equals("nömrə")
                    || v.equals("telefon") || v.equals("number");
        }

        private String[] splitCsv(String line) {
            List<String> out = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean inQuotes = false;
            for (char c : line.toCharArray()) {
                if (c == '"') { inQuotes = !inQuotes; continue; }
                if (c == ',' && !inQuotes) { out.add(cur.toString().trim()); cur.setLength(0); continue; }
                cur.append(c);
            }
            out.add(cur.toString().trim());
            return out.toArray(new String[0]);
        }

        private String safe(String s) {
            if (s == null) return "";
            return s.replace(",", " ").replace("\n", " ").trim();
        }

        private String mask(String phone) {
            if (phone == null || phone.length() < 8) return "****";
            return phone.substring(0, phone.length() - 2) + "..";
        }
    }
}
