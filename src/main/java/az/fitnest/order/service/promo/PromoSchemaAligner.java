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
        if ("_uuid".equals(udt("promo_campaign", "plan_ids"))) {
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
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pg_constraint WHERE conname = 'promo_campaign_popup_cta_target_check'",
                    Integer.class);
            if (count != null && count > 0) {
                String def = jdbc.queryForObject(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'promo_campaign_popup_cta_target_check'",
                        String.class);
                if (def != null && !def.contains("PROMO_DETAIL")) {
                    jdbc.execute("ALTER TABLE promo_campaign_popup DROP CONSTRAINT promo_campaign_popup_cta_target_check");
                    jdbc.execute("ALTER TABLE promo_campaign_popup ADD CONSTRAINT promo_campaign_popup_cta_target_check " +
                            "CHECK (cta_target IN ('PLANS', 'PLAN_DETAIL', 'URL', 'PROMO_DETAIL'))");
                    log.info("Promo schema: extended cta_target check with PROMO_DETAIL");
                }
            }
        } catch (Exception e) {
            log.warn("Promo schema: cta_target check alignment skipped: {}", e.getMessage());
        }
    }

    private void alignIndexes() {
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_promo_grant_reserved_order ON promo_grant(reserved_order_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_promo_grant_redeemed_order ON promo_grant(redeemed_order_id)");
    }

    private void seedBakcell() {
        try {
            jdbc.update("INSERT INTO promo_partner (id, name, logo_url, brand_color, agreement_ref, " +
                    "retention_days, default_prefixes, status) VALUES " +
                    "('11111111-1111-1111-1111-111111111111', 'Bakcell', " +
                    "'https://cdn.fitnest.az/partners/bakcell.svg', '#E4002B', 'BAK-2026-001', 90, " +
                    "ARRAY['55','99'], 'ACTIVE') ON CONFLICT (name) DO NOTHING");
        } catch (Exception e) {
            log.warn("Promo schema: bakcell seed skipped: {}", e.getMessage());
        }
    }
}
