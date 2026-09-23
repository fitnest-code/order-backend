package az.fitnest.order.dto.freeze;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResumeCommitRequest {
    private Integer expectedUsedDays;
}
