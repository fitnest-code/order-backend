package az.fitnest.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class CampaignPushRequest {

    @NotNull
    @Valid
    private Map<String, LocalizedText> contents;

    @Data
    public static class LocalizedText {
        @NotBlank
        private String title;
        @NotBlank
        private String body;
    }
}
