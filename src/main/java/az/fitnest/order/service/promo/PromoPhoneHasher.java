package az.fitnest.order.service.promo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@Service
@RequiredArgsConstructor
@Slf4j
public class PromoPhoneHasher {

    @Value("${promo.hmac.key}")
    private String hmacKey;

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    public String hashPhone(String normalizedPhone) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec secretKeySpec = new SecretKeySpec(hmacKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(secretKeySpec);
            byte[] hashBytes = mac.doFinal(normalizedPhone.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Failed to hash phone number", e);
            throw new IllegalStateException("Phone hashing failed", e);
        }
    }

    public boolean verifyHash(String normalizedPhone, String expectedHash) {
        String computedHash = hashPhone(normalizedPhone);
        return computedHash.equals(expectedHash);
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public String normalizePhone(String phone) {
        if (phone == null) return null;

        String cleaned = phone.trim()
                .replaceAll("[\\s\\-\\(\\)\\.]", "")
                .replace("+", "");

        // Handle different formats
        if (cleaned.startsWith("994")) {
            cleaned = "+" + cleaned;
        } else if (cleaned.startsWith("0") && cleaned.length() == 10) {
            cleaned = "+994" + cleaned.substring(1);
        } else if (cleaned.length() == 9) {
            cleaned = "+994" + cleaned;
        } else if (cleaned.length() == 10 && !cleaned.startsWith("994")) {
            cleaned = "+994" + cleaned;
        } else if (!cleaned.startsWith("+")) {
            cleaned = "+" + cleaned;
        }

        // Validate: +994 + 9 digits
        if (!cleaned.matches("^\\+994\\d{9}$")) {
            return null;
        }

        return cleaned;
    }

    /**
     * True when the normalised number matches one of the campaign's allowed
     * prefixes. Prefixes may be stored as "55", "055", "99455" or "+994 55".
     * An empty prefix list means the campaign is not prefix-restricted.
     */
    public boolean matchesAllowedPrefix(String normalizedPhone, String[] allowedPrefixes) {
        if (normalizedPhone == null || !normalizedPhone.matches("^\\+994\\d{9}$")) {
            return false;
        }
        if (allowedPrefixes == null || allowedPrefixes.length == 0) {
            return true;
        }

        String nationalNumber = normalizedPhone.substring(4); // drop +994
        for (String allowed : allowedPrefixes) {
            if (allowed == null) continue;
            String digits = allowed.replaceAll("\\D", "");
            if (digits.startsWith("994") && digits.length() > 3) {
                digits = digits.substring(3);
            } else if (digits.startsWith("0") && digits.length() > 1) {
                digits = digits.substring(1);
            }
            if (!digits.isEmpty() && nationalNumber.startsWith(digits)) {
                return true;
            }
        }
        return false;
    }

    public boolean isValidAzerbaijanMobile(String normalizedPhone, String[] allowedPrefixes) {
        if (normalizedPhone == null || !normalizedPhone.matches("^\\+994\\d{9}$")) {
            return false;
        }

        String prefix = normalizedPhone.substring(4, 6); // +994XX

        if (allowedPrefixes != null && allowedPrefixes.length > 0) {
            for (String allowed : allowedPrefixes) {
                if (allowed.equals(prefix)) {
                    return true;
                }
            }
            return false;
        }

        // Default Azerbaijan mobile prefixes
        String[] defaultPrefixes = {"10", "50", "51", "55", "70", "77", "99"};
        for (String defaultPrefix : defaultPrefixes) {
            if (defaultPrefix.equals(prefix)) {
                return true;
            }
        }
        return false;
    }
}