package az.fitnest.order.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserFilterRequest {
    private Long packageId;
    private Integer durationMonths;
    private String subscriptionStatus;
    private String sortBy;
}
