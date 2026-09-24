package az.fitnest.order.service.freeze;

import az.fitnest.order.dto.freeze.FreezePreviewResponse;
import az.fitnest.order.model.entity.FreezePreview;
import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.repository.FreezePreviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class FreezePreviewService {

    private final FreezePreviewRepository previewRepository;

    @Transactional
    public FreezePreviewResponse createPreview(Long userId, Subscription subscription, int requestedDays) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime freezeStartAt = now;
        // FIX-10: BRD §2.3 — 1 freeze day = exactly 86400 seconds
        LocalDateTime freezePlanEndAt = now.plusSeconds((long) requestedDays * 86400L);
        LocalDateTime currentExpiryDate = subscription.getEndAt();
        LocalDateTime newExpiryDate = currentExpiryDate != null
                ? currentExpiryDate.plusSeconds((long) requestedDays * 86400L)
                : freezePlanEndAt;
        // FIX-16: Resume quote TTL = 5 minutes (adequate for commit window)
        LocalDateTime expiresAt = now.plusMinutes(5);

        FreezePreview preview = FreezePreview.builder()
                .subscriptionId(subscription.getSubscriptionId())
                .userId(userId)
                .requestedDays(requestedDays)
                .planEndAt(freezePlanEndAt)
                .expiryAfter(newExpiryDate)
                .expectedVersion(subscription.getVersion())
                .expiresAt(expiresAt)
                .build();

        preview = previewRepository.save(preview);

        return new FreezePreviewResponse(
                String.valueOf(preview.getId()),
                subscription.getSubscriptionId(),
                requestedDays,
                currentExpiryDate,
                newExpiryDate,
                freezeStartAt,
                freezePlanEndAt,
                expiresAt);
    }

    @Transactional(readOnly = true)
    public FreezePreview getValidPreview(Long previewId, Long userId) {
        FreezePreview preview = previewRepository.findByIdAndUserId(previewId, userId)
                .orElseThrow(() -> new az.fitnest.order.exception.BadRequestException("error.freeze.preview_not_found"));

        if (preview.isExpired()) {
            throw new az.fitnest.order.exception.ConflictException("error.freeze.preview_changed");
        }

        return preview;
    }
}
