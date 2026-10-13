package az.fitnest.order.service.promo;

import az.fitnest.order.dto.promo.AdminPromoAudienceUploadResponse;
import az.fitnest.order.dto.promo.AdminPromoAudienceUploadStatusResponse;
import az.fitnest.order.dto.promo.AdminPromoAudienceProblemRow;
import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.exception.ResourceNotFoundException;
import az.fitnest.order.model.entity.promo.*;
import az.fitnest.order.repository.promo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PromoAudienceUploadService {

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024; // 10 MB
    private static final int MAX_ROWS = 200_000;

    private final PromoAudienceUploadRepository uploadRepository;
    private final PromoAudienceNumberRepository numberRepository;
    private final PromoCampaignRepository campaignRepository;
    private final PromoPhoneHasher phoneHasher;
    private final PromoPhoneEncryption phoneEncryption;
    private final PromoGrantService grantService;
    private final PromoAuditLogService auditLogService;

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

        PromoAudienceUpload upload = new PromoAudienceUpload();
        upload.setCampaignId(campaignId);
        upload.setFileName(filename);
        upload.setMode(mode);
        upload.setUploadedBy(adminId);
        upload.setStatus(PromoUploadStatus.PROCESSING);
        upload = uploadRepository.save(upload);

        try {
            processFile(campaign, upload, file, mode);
            upload.setStatus(PromoUploadStatus.DONE);
        } catch (Exception e) {
            log.error("Failed processing upload {}", upload.getId(), e);
            upload.setStatus(PromoUploadStatus.FAILED);
        }
        upload = uploadRepository.save(upload);
        auditLogService.log(adminId, "UPLOAD", "PROMO_AUDIENCE", campaignId,
                null, "{\"file\":\"" + filename + "\",\"mode\":\"" + mode + "\",\"valid\":" + upload.getValid() + "}", null, null);
        return map(upload);
    }

    private void processFile(PromoCampaign campaign, PromoAudienceUpload upload, MultipartFile file, PromoUploadMode mode) {
        List<String> problemRows = new ArrayList<>();
        Set<String> seenInFile = new HashSet<>();
        List<PromoAudienceNumber> toInsert = new ArrayList<>();
        int duplicates = 0, invalid = 0, total = 0;

        String[] allowedPrefixes = campaign.getAllowedPrefixes();
        if ((allowedPrefixes == null || allowedPrefixes.length == 0) && campaign.getPartnerId() != null) {
            allowedPrefixes = new String[0];
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            int phoneColumn = 0;
            while ((line = reader.readLine()) != null) {
                if (total >= MAX_ROWS) break;
                if (first) {
                    first = false;
                    phoneColumn = detectPhoneColumn(line);
                    String[] headers = splitCsv(line);
                    if (phoneColumn < 0 && headers.length > 0) {
                        // If we can't find a phone column, assume first column
                        phoneColumn = 0;
                    }
                    continue;
                }
                total++;
                String[] cols = splitCsv(line);
                if (phoneColumn >= cols.length) {
                    invalid++;
                    problemRows.add(String.valueOf(total) + ",,MISSING_VALUE");
                    continue;
                }
                String raw = cols[phoneColumn];
                String normalized = phoneHasher.normalizePhone(raw);
                if (normalized == null) {
                    invalid++;
                    problemRows.add(total + "," + raw + ",INVALID_FORMAT");
                    continue;
                }
                if (!phoneHasher.isValidAzerbaijanMobile(normalized, allowedPrefixes)) {
                    invalid++;
                    problemRows.add(total + "," + raw + ",INVALID_PREFIX");
                    continue;
                }
                String hash = phoneHasher.hashPhone(normalized);
                if (seenInFile.contains(hash)) {
                    duplicates++;
                    problemRows.add(total + "," + mask(normalized) + ",DUPLICATE_IN_FILE");
                    continue;
                }
                seenInFile.add(hash);
                if (numberRepository.existsByCampaignIdAndPhoneHash(campaign.getId(), hash)) {
                    duplicates++;
                    problemRows.add(total + "," + mask(normalized) + ",DUPLICATE_IN_CAMPAIGN");
                    continue;
                }
                PromoAudienceNumber n = new PromoAudienceNumber();
                n.setCampaignId(campaign.getId());
                n.setPhoneHash(hash);
                n.setPhoneEncrypted(phoneEncryption.encrypt(normalized));
                n.setUploadId(upload.getId());
                toInsert.add(n);
            }
        } catch (IOException e) {
            throw new BadRequestException("error.upload_read_failed");
        }

        upload.setTotalRows(total);
        upload.setValid(toInsert.size());
        upload.setDuplicates(duplicates);
        upload.setInvalid(invalid);

        if (mode == PromoUploadMode.REPLACE) {
            numberRepository.deleteByCampaignId(campaign.getId());
        }

        if (!toInsert.isEmpty()) {
            numberRepository.saveAll(toInsert);
        }

        upload.setExistingUsers(0);
        // Store the problem rows as a downloadable CSV report
        if (!problemRows.isEmpty()) {
            StringBuilder csv = new StringBuilder("row,phone,issue\n");
            for (String p : problemRows) csv.append(p).append("\n");
            upload.setReportCsv(csv.toString());
        }

        // Create grants for matching existing users on publish/append handled in grant service
        if (mode == PromoUploadMode.APPEND && campaign.getStatus() == PromoCampaignStatus.ACTIVE) {
            grantService.createGrantsForNumbers(campaign.getId(),
                    toInsert.stream().map(PromoAudienceNumber::getPhoneHash).collect(Collectors.toList()));
        }
    }

    private int detectPhoneColumn(String headerLine) {
        String[] headers = splitCsv(headerLine);
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].trim().toLowerCase();
            if (h.equals("phone") || h.equals("msisdn") || h.equals("nomre") || h.equals("nomre".toLowerCase())) {
                return i;
            }
        }
        return -1;
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

    private String mask(String phone) {
        if (phone == null || phone.length() < 8) return "****";
        return phone.substring(0, phone.length() - 2) + "..";
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
}
