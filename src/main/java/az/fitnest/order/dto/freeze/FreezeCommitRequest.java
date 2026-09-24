package az.fitnest.order.dto.freeze;

import jakarta.validation.constraints.NotBlank;

/** Immutable commit body — previewId from a prior preview call. */
public record FreezeCommitRequest(
        @NotBlank(message = "error.freeze.preview_not_found")
        String previewId
) {}
