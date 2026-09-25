package az.fitnest.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampaignDetailDto {
    private Long id;
    private String code;
    private String title;
    private String description;
    private String bannerImageUrl;
    private String ctaLabel;
    private String ctaTarget;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private List<CampaignOfferDto> offers;
    private List<CampaignTermDto> terms;
}
