package az.fitnest.order.service.freeze;

import az.fitnest.order.model.entity.FreezeIdempotencyRecord;
import az.fitnest.order.repository.FreezeIdempotencyRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class FreezeIdempotencyService {

    private final FreezeIdempotencyRecordRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Optional<FreezeIdempotencyRecord> findRecord(Long userId, String endpoint, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return idempotencyRepository.findByUserIdAndEndpointAndIdempotencyKey(userId, endpoint, idempotencyKey)
                .filter(r -> !r.isExpired());
    }

    @Transactional
    public void saveRecord(Long userId, String endpoint, String idempotencyKey, Object requestPayload, Object responsePayload) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }

        try {
            String requestJson = objectMapper.writeValueAsString(requestPayload);
            String responseJson = objectMapper.writeValueAsString(responsePayload);
            String hash = hashString(requestJson);

            FreezeIdempotencyRecord record = FreezeIdempotencyRecord.builder()
                    .userId(userId)
                    .endpoint(endpoint)
                    .idempotencyKey(idempotencyKey)
                    .requestHash(hash)
                    .responseBody(responseJson)
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .build();

            idempotencyRepository.save(record);
        } catch (Exception e) {
            log.error("Failed to save idempotency record for key={}: {}", idempotencyKey, e.getMessage());
        }
    }

    public String hashString(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
