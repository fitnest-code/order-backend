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
public class CustomerDashboardClient {

    private final RestClient restClient;
    private final boolean enabled;
    private final String summaryPath;
    private final String growthPath;

    public CustomerDashboardClient(
            @Value("${dashboard.clients.customer.url:}") String baseUrl,
            @Value("${dashboard.clients.customer.enabled:false}") boolean enabled,
            @Value("${dashboard.clients.customer.summary-path:/api/v1/internal/customers/summary}") String summaryPath,
            @Value("${dashboard.clients.customer.growth-path:/api/v1/internal/customers/growth}") String growthPath,
            RestClient.Builder builder
    ) {
        this.enabled = enabled && baseUrl != null && !baseUrl.isBlank();
        this.summaryPath = summaryPath;
        this.growthPath = growthPath;
        this.restClient = builder.baseUrl(baseUrl == null ? "" : baseUrl).build();
    }

    public Optional<CustomerSummaryResponse> getSummary(Long gymId, String authHeader) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            CustomerSummaryResponse response = restClient.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder.path(summaryPath);
                        if (gymId != null) {
                            builder.queryParam("gym_id", gymId);
                        }
                        return builder.build();
                    })
                    .header(HttpHeaders.AUTHORIZATION, authHeader)
                    .retrieve()
                    .body(CustomerSummaryResponse.class);
            return Optional.ofNullable(response);
        } catch (RuntimeException ex) {
            log.warn("Customer dashboard summary call failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public Optional<CustomerGrowthResponse> getGrowth(String metric, String period, LocalDate dateFrom, LocalDate dateTo,
                                                      Long gymId, String authHeader) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            CustomerGrowthResponse response = restClient.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder.path(growthPath)
                                .queryParam("metric", metric)
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
                    .body(CustomerGrowthResponse.class);
            return Optional.ofNullable(response);
        } catch (RuntimeException ex) {
            log.warn("Customer dashboard growth call failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public record CustomerSummaryResponse(
            @JsonProperty("active_customers") long activeCustomers,
            @JsonProperty("previous_period_customers") long previousPeriodCustomers
    ) {
    }

    public record CustomerGrowthResponse(
            @JsonProperty("data_points") List<CustomerGrowthPoint> dataPoints
    ) {
    }

    public record CustomerGrowthPoint(
            @JsonProperty("timestamp") String timestamp,
            @JsonProperty("count") long count
    ) {
    }
}
