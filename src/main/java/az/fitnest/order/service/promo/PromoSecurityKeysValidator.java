package az.fitnest.order.service.promo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * NFR-3: phone HMAC / encryption keys must come from Vault in production.
 * Fails fast when {@code promo.keys.require-configured=true} (set it in prod),
 * otherwise logs a loud warning so dev never silently ships defaults.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PromoSecurityKeysValidator implements ApplicationRunner {

    private static final String DEFAULT_HMAC = "fitnest-promo-hmac-change-me";
    private static final String DEFAULT_ENC = "fitnest-promo-enc-change-me!!";

    @Value("${promo.hmac.key:fitnest-promo-hmac-change-me}")
    private String hmacKey;

    @Value("${promo.encryption.key:fitnest-promo-enc-change-me!!}")
    private String encryptionKey;

    @Value("${promo.keys.require-configured:false}")
    private boolean requireConfigured;

    @Override
    public void run(ApplicationArguments args) {
        boolean hmacDefault = DEFAULT_HMAC.equals(hmacKey);
        boolean encDefault = DEFAULT_ENC.equals(encryptionKey);
        if (hmacDefault || encDefault) {
            String msg = "PROMO SECURITY: HMAC/encryption keys are defaults "
                    + "(hmacDefault=" + hmacDefault + ", encDefault=" + encDefault + "). "
                    + "Set PROMO_HMAC_KEY / PROMO_ENCRYPTION_KEY from Vault before production traffic.";
            if (requireConfigured) {
                throw new IllegalStateException(msg);
            }
            log.warn(msg);
        } else {
            log.info("Promo security keys configured (lengths: hmac={}, enc={})",
                    hmacKey.length(), encryptionKey.length());
        }
    }
}
