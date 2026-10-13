package az.fitnest.order.service.promo;

import az.fitnest.order.dto.promo.AdminPromoCampaignNumberItemResponse;
import az.fitnest.order.dto.promo.AdminPromoCampaignNumbersResponse;
import az.fitnest.order.exception.ForbiddenException;
import az.fitnest.order.model.entity.promo.PromoAudienceType;
import az.fitnest.order.model.entity.promo.PromoCampaign;
import az.fitnest.order.model.entity.promo.PromoGrantStatus;
import az.fitnest.order.repository.promo.PromoCampaignRepository;
import az.fitnest.order.util.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A6 eligible-numbers table (12.2 audit, NFR-3 masking).
 * Single SQL per page (no N+1): audience numbers LEFT JOIN grants.
 * Phones are masked by default; full values need PROMO_PII_VIEW and are logged.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PromoNumbersQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final PromoCampaignRepository campaignRepository;
    private final PromoPhoneEncryption phoneEncryption;
    private final PromoAuditLogService auditLogService;
    private final PromoPhoneHasher phoneHasher;

    @Transactional(readOnly = true)
    public AdminPromoCampaignNumbersResponse numbers(UUID campaignId, String phone, String status,
                                                     int page, int size, boolean reveal) {
        PromoCampaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.promo_campaign_not_found"));
        if (campaign.getAudienceType() != PromoAudienceType.UPLOADED_LIST) {
            return AdminPromoCampaignNumbersResponse.builder()
                    .items(List.of()).page(page).size(size).totalElements(0L).totalPages(0).build();
        }
        if (reveal && !UserContext.hasAuthority("PROMO_PII_VIEW")) {
            throw new ForbiddenException("error.forbidden");
        }

        String phoneHash = null;
        if (phone != null && !phone.isBlank()) {
            String normalized = phoneHasher.normalizePhone(phone);
            phoneHash = normalized != null ? phoneHasher.hashPhone(normalized) : "__no_match__";
        }

        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("WHERE n.campaign_id = ? ");
        args.add(campaignId);
        if (phoneHash != null) {
            where.append("AND n.phone_hash = ? ");
            args.add(phoneHash);
        }
        if (status != null && !status.isBlank()) {
            switch (status.toUpperCase()) {
                case "REDEEMED" -> where.append("AND g.status = 'REDEEMED' ");
                case "REVOKED" -> where.append("AND g.status = 'REVOKED' ");
                case "REGISTERED" -> where.append("AND g.id IS NOT NULL AND g.status NOT IN ('REDEEMED','REVOKED') ");
                case "NOT_REGISTERED" -> where.append("AND g.id IS NULL ");
                default -> { /* unknown filter ignored */ }
            }
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM promo_audience_number n LEFT JOIN promo_grant g " +
                        "ON g.campaign_id = n.campaign_id AND g.phone_hash = n.phone_hash " + where,
                Long.class, args.toArray());

        int offset = page * size;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT n.phone_hash, n.phone_encrypted, g.status AS gstatus, " +
                        "g.popup_status, g.redeemed_at, g.user_id " +
                        "FROM promo_audience_number n LEFT JOIN promo_grant g " +
                        "ON g.campaign_id = n.campaign_id AND g.phone_hash = n.phone_hash " +
                        where + "ORDER BY n.phone_hash LIMIT ? OFFSET ?",
                concat(args, List.of(size, offset)).toArray());

        List<AdminPromoCampaignNumberItemResponse> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String hash = (String) row.get("phone_hash");
            String gstatus = (String) row.get("gstatus");
            String displayStatus = gstatus == null ? "NOT_REGISTERED"
                    : (gstatus.equals("GRANTED") || gstatus.equals("RESERVED") || gstatus.equals("EXPIRED"))
                        ? "REGISTERED" : gstatus;
            String phoneOut = null;
            try {
                byte[] enc = (byte[]) row.get("phone_encrypted");
                if (enc != null) {
                    String full = phoneEncryption.decrypt(enc);
                    phoneOut = reveal ? full : phoneEncryption.maskPhone(full);
                }
            } catch (Exception e) {
                log.warn("Failed decrypting audience phone for campaign {}", campaignId);
            }
            Object popupStatus = row.get("popup_status");
            Object redeemedAt = row.get("redeemed_at");
            Object userId = row.get("user_id");
            items.add(AdminPromoCampaignNumberItemResponse.builder()
                    .phone(phoneOut)
                    .phoneHash(hash)
                    .status(displayStatus)
                    .registered(gstatus != null)
                    .sawPopup("SEEN".equals(popupStatus))
                    .redeemedAt(redeemedAt instanceof Timestamp ts ? ts.toInstant().atOffset(OffsetDateTime.now().getOffset()) : null)
                    .userId(userId instanceof Number num ? num.longValue() : null)
                    .build());
        }

        if (reveal) {
            auditLogService.log(UserContext.getCurrentUserId(), "PII_REVEAL", "PROMO_AUDIENCE", campaignId,
                    null, "{\"page\":" + page + ",\"size\":" + size + "}", null, null);
        }

        int totalPages = size > 0 ? (int) Math.ceil((double) (total != null ? total : 0) / size) : 0;
        return AdminPromoCampaignNumbersResponse.builder()
                .items(items).page(page).size(size)
                .totalElements(total != null ? total : 0L).totalPages(totalPages).build();
    }

    @Transactional(readOnly = true)
    public String revealPhone(UUID campaignId, String phoneHash) {
        if (!UserContext.hasAuthority("PROMO_PII_VIEW")) {
            throw new ForbiddenException("error.forbidden");
        }
        byte[] enc = jdbcTemplate.query(
                "SELECT phone_encrypted FROM promo_audience_number WHERE campaign_id = ? AND phone_hash = ?",
                ps -> { ps.setObject(1, campaignId); ps.setString(2, phoneHash); },
                rs -> rs.next() ? rs.getBytes(1) : null);
        if (enc == null) {
            throw new az.fitnest.order.exception.ResourceNotFoundException("error.promo_number_not_found");
        }
        String full = phoneEncryption.decrypt(enc);
        auditLogService.log(UserContext.getCurrentUserId(), "PII_REVEAL", "PROMO_NUMBER", campaignId,
                "{\"phoneHash\":\"" + phoneHash + "\"}", null, null, null);
        return full;
    }

    private List<Object> concat(List<Object> a, List<Object> b) {
        List<Object> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
