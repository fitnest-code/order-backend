package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminPromoCampaignNumbersResponse {

    private List<AdminPromoCampaignNumberItemResponse> items;
    private Integer page;
    private Integer size;
    private Long totalElements;
    private Integer totalPages;
}