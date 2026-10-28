package az.fitnest.order.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.util.*;

/** Resolves the signed-in account's phone from the internal directory, never from the mobile request. */
@Component
public class PlanCampaignDirectoryClient {
    private static final Logger log = LoggerFactory.getLogger(PlanCampaignDirectoryClient.class);
    private final RestTemplate http;
    private final String baseUrl;

    public PlanCampaignDirectoryClient(@Value("${user.backend.url:http://user-backend:8080}") String baseUrl) {
        this.baseUrl = baseUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.http = new RestTemplate(factory);
    }

    public Optional<String> phoneFor(Long userId) {
        if (userId == null) return Optional.empty();
        try {
            List<DirectoryUser> users = http.exchange(baseUrl + "/api/v1/internal/users/batch",
                    HttpMethod.POST, new HttpEntity<>(Map.of("userIds", List.of(userId))),
                    new ParameterizedTypeReference<List<DirectoryUser>>() {}).getBody();
            if (users == null) return Optional.empty();
            return users.stream().filter(user -> Objects.equals(user.userId(), userId))
                    .map(DirectoryUser::mobile).filter(Objects::nonNull).findFirst();
        } catch (Exception e) {
            // Fail closed: directory outages must never expose a targeted banner.
            log.warn("Plan campaign directory unavailable for userId={} ({})", userId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DirectoryUser(Long userId, String mobile) {}
}
