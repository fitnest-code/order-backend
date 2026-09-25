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
public class FreezeTermsResponse {

    @JsonProperty("html_content")
    private String htmlContent;
}
