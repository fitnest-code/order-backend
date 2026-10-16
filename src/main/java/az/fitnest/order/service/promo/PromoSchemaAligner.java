package az.fitnest.order.service.promo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Aligns promo schema on databases where Flyway is not managing migrations
 * (e.g. dev runs ddl-auto:update, which creates tables/columns but never
 * alters column types). Every statement is idempotent and guarded by
 * information_schema checks, so it is a no-op once Flyway V19-V22 apply.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PromoSchemaAligner implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        try {
            alignArrays();
            alignConstraints();
            alignIndexes();
            seedBakcell();
        } catch (Exception e) {
            log.error("Promo schema alignment failed (non-fatal, investigate)", e);
        }
    }

    private String udt(String table, String column) {
        try {
            String v = jdbc.queryForObject(
                    "SELECT udt_name FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                    String.class, table, column);
            return v != null ? v : "";
        } catch (Exception e) {
            return "";
        }
    }

    private void alignArrays() {
        String planIdsType = udt("promo_campaign", "plan_ids");
        log.info("Promo schema: promo_campaign.plan_ids current type = '{}'", planIdsType);
        if ("_uuid".equals(planIdsType)) {
            jdbc.execute("ALTER TABLE promo_campaign ALTER COLUMN plan_ids TYPE TEXT[] USING plan_ids::text[]");
            log.info("Promo schema: converted promo_campaign.plan_ids uuid[] -> text[]");
        }
        String popupPlan = udt("promo_campaign_popup", "plan_id");
        if ("uuid".equals(popupPlan)) {
            jdbc.execute("ALTER TABLE promo_campaign_popup ALTER COLUMN plan_id TYPE TEXT USING plan_id::text");
            log.info("Promo schema: converted promo_campaign_popup.plan_id uuid -> text");
        }
    }

    private void alignConstraints() {
        // The IN-list check is redundant with the PromoCtaTarget enum (validated
        // in Java). DDL-managed DBs may lack it, Flyway DBs have the V22 version;
        // either way a plain DROP is safe and sufficient.
        try {
            jdbc.execute("ALTER TABLE promo_campaign_popup DROP CONSTRAINT IF EXISTS promo_campaign_popup_cta_target_check");
        } catch (Exception e) {
            log.warn("Promo schema: cta_target check drop skipped: {}", e.getMessage());
        }
    }

    private void alignIndexes() {
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_promo_grant_reserved_order ON promo_grant(reserved_order_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_promo_grant_redeemed_order ON promo_grant(redeemed_order_id)");
    }

    private void seedBakcell() {
        try {
            // created_at/updated_at supplied explicitly: ddl-auto-created tables
            // have no DEFAULT now() unlike the Flyway scripts.
            jdbc.update("INSERT INTO promo_partner (id, name, logo_url, brand_color, agreement_ref, " +
                    "retention_days, default_prefixes, status, created_at, updated_at) VALUES " +
                    "('11111111-1111-1111-1111-111111111111', 'Bakcell', " +
                    "'https://cdn.fitnest.az/partners/bakcell.svg', '#E4002B', 'BAK-2026-001', 90, " +
                    "ARRAY['55','99'], 'ACTIVE', now(), now()) ON CONFLICT (name) DO NOTHING");
        } catch (Exception e) {
            log.warn("Promo schema: bakcell seed skipped: {}", e.getMessage());
        }
    }
}
