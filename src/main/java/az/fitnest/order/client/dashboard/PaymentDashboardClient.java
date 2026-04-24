package az.fitnest.order.client.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class PaymentDashboardClient {

    private final RestClient restClient;
    private final boolean enabled;
    private final String revenuePath;

    public PaymentDashboardClient(
            @Value("${dashboard.clients.payment.url:}") String baseUrl,
            @Value("${dashboard.clients.payment.enabled:false}") boolean enabled,
            @Value("${dashboard.clients.payment.revenue-path:/api/v1/internal/payments/revenue}") String revenuePath,
            RestClient.Builder builder
    ) {
        this.enabled = enabled && baseUrl != null && !baseUrl.isBlank();
        this.revenuePath = revenuePath;
        this.restClient = builder.baseUrl(baseUrl == null ? "" : baseUrl).build();
    }

    public Optional<RevenueResponse> getRevenue(String packageCode, String period,
                                                LocalDate dateFrom, LocalDate dateTo,
                                                Long gymId, String authHeader) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            RevenueResponse response = restClient.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder.path(revenuePath)
                                .queryParam("package", packageCode)
                                .queryParam("period", period);
                        if (dateFrom != null) {
                            builder.queryParam("date_from", dateFrom);
                        }
                        if (dateTo != null) {
                            builder.queryParam("date_to", dateTo);
                        }
                        if (gymId != null) {
                            builder.queryParam("gym_id", gymId);
                        }
                        return builder.build();
                    })
                    .header(HttpHeaders.AUTHORIZATION, authHeader)
                    .retrieve()
                    .body(RevenueResponse.class);
            return Optional.ofNullable(response);
        } catch (RuntimeException ex) {
            log.warn("Payment dashboard revenue call failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public record RevenueResponse(
            @JsonProperty("currency") String currency,
            @JsonProperty("data_points") List<RevenueDataPoint> dataPoints
    ) {
    }

    public record RevenueDataPoint(
            @JsonProperty("timestamp") String timestamp,
            @JsonProperty("amount") double amount,
            @JsonProperty("status") String status
    ) {
    }
}
