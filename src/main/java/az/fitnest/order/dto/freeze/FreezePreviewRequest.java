package az.fitnest.order.dto.freeze;

import jakarta.validation.constraints.Min;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FreezePreviewRequest {
    @Min(value = 1, message = "error.freeze.invalid_days")
    private int days;
}
