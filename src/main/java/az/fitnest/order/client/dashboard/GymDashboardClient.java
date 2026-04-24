package az.fitnest.order.client.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

@Slf4j
@Component
public class GymDashboardClient {

    private final RestClient restClient;
    private final boolean enabled;
    private final String countPath;

    public GymDashboardClient(
            @Value("${dashboard.clients.gym.url:}") String baseUrl,
            @Value("${dashboard.clients.gym.enabled:false}") boolean enabled,
            @Value("${dashboard.clients.gym.count-path:/api/v1/internal/gyms/count}") String countPath,
            RestClient.Builder builder
    ) {
        this.enabled = enabled && baseUrl != null && !baseUrl.isBlank();
        this.countPath = countPath;
        this.restClient = builder.baseUrl(baseUrl == null ? "" : baseUrl).build();
    }

    public Optional<PartnerSummaryResponse> getPartnerCount(String authHeader) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            PartnerSummaryResponse response = restClient.get()
                    .uri(countPath)
                    .header(HttpHeaders.AUTHORIZATION, authHeader)
                    .retrieve()
                    .body(PartnerSummaryResponse.class);
            return Optional.ofNullable(response);
        } catch (RuntimeException ex) {
            log.warn("Gym dashboard count call failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public record PartnerSummaryResponse(
            @JsonProperty("total_gyms") long totalGyms,
            @JsonProperty("active_gyms") long activeGyms,
            @JsonProperty("previous_period_gyms") long previousPeriodGyms
    ) {
    }
}
