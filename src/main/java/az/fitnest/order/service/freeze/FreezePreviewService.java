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
        LocalDateTime freezePlanEndAt = now.plusDays(requestedDays);
        LocalDateTime currentExpiryDate = subscription.getEndAt();
        LocalDateTime newExpiryDate = currentExpiryDate != null ? currentExpiryDate.plusDays(requestedDays) : freezePlanEndAt;
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

        return FreezePreviewResponse.builder()
                .previewId(String.valueOf(preview.getId()))
                .subscriptionId(subscription.getSubscriptionId())
                .requestedDays(requestedDays)
                .currentExpiryDate(currentExpiryDate)
                .newExpiryDate(newExpiryDate)
                .freezeStartAt(freezeStartAt)
                .freezePlanEndAt(freezePlanEndAt)
                .expiresAt(expiresAt)
                .build();
    }

    @Transactional(readOnly = true)
    public FreezePreview getValidPreview(Long previewId, Long userId) {
        FreezePreview preview = previewRepository.findByIdAndUserId(previewId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Preview not found or expired"));

        if (preview.isExpired()) {
            throw new IllegalStateException("Preview has expired. Please create a new preview.");
        }

        return preview;
    }
}
