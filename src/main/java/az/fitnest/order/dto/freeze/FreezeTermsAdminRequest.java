package az.fitnest.order.dto.freeze;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FreezeTermsAdminRequest {

    @Schema(description = "HTML content in Azerbaijani", example = "<h3>Dondurma Qaydaları</h3><p>...</p>")
    @JsonProperty("html_content_az")
    private String htmlContentAz;

    @Schema(description = "HTML content in English", example = "<h3>Freeze Rules</h3><p>...</p>")
    @JsonProperty("html_content_en")
    private String htmlContentEn;

    @Schema(description = "HTML content in Russian", example = "<h3>Правила заморозки</h3><p>...</p>")
    @JsonProperty("html_content_ru")
    private String htmlContentRu;
}
