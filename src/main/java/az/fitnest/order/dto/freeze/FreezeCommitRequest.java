package az.fitnest.order.dto.freeze;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FreezeCommitRequest {
    @NotBlank(message = "Preview ID is required")
    private String previewId;
}
