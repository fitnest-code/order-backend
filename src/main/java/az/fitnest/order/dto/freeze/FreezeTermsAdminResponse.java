package az.fitnest.order.dto.freeze;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FreezeTermsAdminResponse {

    @JsonProperty("html_content_az")
    private String htmlContentAz;

    @JsonProperty("html_content_en")
    private String htmlContentEn;

    @JsonProperty("html_content_ru")
    private String htmlContentRu;
}
