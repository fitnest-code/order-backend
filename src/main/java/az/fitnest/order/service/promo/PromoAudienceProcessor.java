package az.fitnest.order.service.promo;

import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.model.entity.promo.*;
import az.fitnest.order.repository.promo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Async audience-file worker (own bean so the Spring @Async proxy applies).
 * Complexity: O(n) single pass + O(1) amortized set lookups; one SELECT to
 * preload existing hashes instead of O(n) EXISTS queries.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PromoAudienceProcessor {

    static final long MAX_FILE_SIZE = 10L * 1024 * 1024; // 10 MB
    static final int MAX_ROWS = 200_000;
    private static final int INSERT_BATCH = 1_000;
    private static final int PREVIEW_ROWS = 50;

    private final PromoAudienceUploadRepository uploadRepository;
    private final PromoAudienceNumberRepository numberRepository;
    private final PromoCampaignRepository campaignRepository;
    private final PromoPhoneHasher phoneHasher;
    private final PromoPhoneEncryption phoneEncryption;

    @Async("promoUploadExecutor")
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
                problemRows.add(total + "," + phoneEncryption.maskPhone(normalized) + ",DUPLICATE_IN_FILE");
                continue;
            }
            if (existing.contains(hash)) {
                duplicates++;
                problemRows.add(total + "," + phoneEncryption.maskPhone(normalized) + ",DUPLICATE_IN_CAMPAIGN");
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
        // Note: grants for newly listed numbers arrive via the
        // user.phone.verified consumer (or the manual admin grant endpoint).
        // No batch phone->user directory exists, so nothing to do here.
    }

    private List<String> readCsv(byte[] bytes) throws Exception {
        List<String> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            int phoneColumn = 0;
            char delimiter = ',';
            while ((line = reader.readLine()) != null) {
                if (first) {
                    first = false;
                    delimiter = detectDelimiter(line);
                    phoneColumn = detectPhoneColumn(line, delimiter);
                    if (phoneColumn < 0) phoneColumn = 0;
                    continue;
                }
                String[] cols = splitLine(line, delimiter);
                out.add(phoneColumn < cols.length ? cols[phoneColumn] : "");
            }
        }
        if (out.isEmpty()) throw new BadRequestException("error.upload_no_phone_column");
        return out;
    }

    private List<String> readXlsx(byte[] bytes) throws Exception {
        List<String> out = new ArrayList<>();
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K' || bytes[2] != 0x03 || bytes[3] != 0x04) {
            throw new BadRequestException("error.upload_bad_format");
        }
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

    private char detectDelimiter(String headerLine) {
        int commas = countChar(headerLine, ',');
        int semis = countChar(headerLine, ';');
        int tabs = countChar(headerLine, '\t');
        if (semis > commas && semis >= tabs) return ';';
        if (tabs > commas && tabs > semis) return '\t';
        return ',';
    }

    private int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    private int detectPhoneColumn(String headerLine, char delimiter) {
        String[] headers = splitLine(headerLine, delimiter);
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

    private String[] splitLine(String line, char delimiter) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (char c : line.toCharArray()) {
            if (c == '"') { inQuotes = !inQuotes; continue; }
            if (c == delimiter && !inQuotes) { out.add(cur.toString().trim()); cur.setLength(0); continue; }
            cur.append(c);
        }
        out.add(cur.toString().trim());
        return out.toArray(new String[0]);
    }

    private String safe(String s) {
        if (s == null) return "";
        return s.replace(",", " ").replace("\n", " ").trim();
    }
}
