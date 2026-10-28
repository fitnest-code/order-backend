package az.fitnest.order.client;

import java.math.BigDecimal;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

@Component
public class GymPolicyV4Client {
  private final RestTemplate http;
  private final String base;

  public GymPolicyV4Client(
      @Value("${catalog.backend.url:http://catalog-backend:8080}") String base) {
    this.base = base;
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(2));
    factory.setReadTimeout(Duration.ofSeconds(4));
    http = new RestTemplate(factory);
  }

  public Quote quote(Long userId, Long gymId, Long packageId) {
    var headers = new HttpHeaders();
    headers.set("X-User-Id", userId.toString());
    try {
      var q =
          http.exchange(
                  base + "/api/v4/gyms/" + gymId + "/membership-quote?packageId=" + packageId,
                  HttpMethod.GET,
                  new HttpEntity<>(headers),
                  Quote.class)
              .getBody();
      if (q == null) throw new IllegalStateException("Empty policy response");
      return q;
    } catch (Exception e) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "GYM_POLICY_UNAVAILABLE", e);
    }
  }

  public record Quote(
      Long gymId,
      String gymName,
      long policyVersion,
      boolean allowed,
      boolean primaryEnabled,
      LocalDateTime primaryDisabledAt,
      BigDecimal entryPrice,
      BigDecimal primaryMonthlyPrice,
      String priceTier,
      String reason,
      java.util.List<LocalDateTime> primaryDisableHistory,
      java.util.List<az.fitnest.order.dto.v4.PrimaryGymV4.AccessWindow> accessWindows) {}
}
