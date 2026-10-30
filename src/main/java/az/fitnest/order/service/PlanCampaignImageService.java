package az.fitnest.order.service;

import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.exception.ResourceNotFoundException;
import az.fitnest.order.model.entity.PlanCampaignImage;
import az.fitnest.order.repository.PlanCampaignImageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

@Service
public class PlanCampaignImageService {
    private final RestTemplate http;
    private final String storageUrl;
    private final String mediaBaseUrl;
    private final PlanCampaignImageRepository images;
    public PlanCampaignImageService(
            @Value("${storage.backend.url:http://storage-backend:8080}") String storageUrl,
            @Value("${plan-campaign.media-base-url:}") String mediaBaseUrl,
            @Value("${spring.profiles.active:development}") String profiles,
            PlanCampaignImageRepository images) {
        this.storageUrl = storageUrl;
        this.images = images;
        this.mediaBaseUrl = mediaBaseUrl.isBlank()
                ? (Arrays.asList(profiles.split(",")).contains("production")
                    ? "https://api.fitnest.az/api/v2/me/plan-campaigns/images/" : "https://api-dev.fitnest.az/api/v2/me/plan-campaigns/images/")
                : mediaBaseUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3)); factory.setReadTimeout(Duration.ofSeconds(30));
        this.http = new RestTemplate(factory);
    }

    public Map<String, String> upload(MultipartFile file, Long adminId) throws IOException {
        if (file.isEmpty() || file.getSize() > 8 * 1024 * 1024) throw new BadRequestException("error.plan_campaign_invalid_image");
        String type = file.getContentType();
        if (type == null || !Set.of("image/png", "image/jpeg", "image/webp").contains(type)) {
            throw new BadRequestException("error.plan_campaign_invalid_image");
        }
        String extension = type.equals("image/png") ? ".png" : type.equals("image/webp") ? ".webp" : ".jpg";
        String filename = UUID.randomUUID() + extension;
        byte[] bytes = file.getBytes();
        // The storage service validates the actual image bytes too.
        ByteArrayResource resource = new ByteArrayResource(bytes) { @Override public String getFilename() { return filename; } };
        var partHeaders = new HttpHeaders(); partHeaders.setContentType(MediaType.parseMediaType(type));
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", new HttpEntity<>(resource, partHeaders)); body.add("directory", "/campaigns/plans");
        var headers = new HttpHeaders(); headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("X-User-Id", String.valueOf(adminId)); headers.set("X-User-Roles", "ROLE_ADMIN");
        Map<?, ?> response = http.postForObject(storageUrl + "/api/v1/files/upload", new HttpEntity<>(body, headers), Map.class);
        Object data = response != null && response.get("data") instanceof Map<?, ?> ? response.get("data") : response;
        if (!(data instanceof Map<?, ?> fileInfo) || fileInfo.get("fsId") == null) {
            throw new IllegalStateException("Storage did not return an image identifier");
        }
        String fsId = fileInfo.get("fsId").toString();
        if (!fsId.matches("\\d+")) throw new IllegalStateException("Invalid storage image identifier");
        PlanCampaignImage image = new PlanCampaignImage(); image.setFsId(fsId);
        image.setUploadedBy(adminId); image.setUploadedAt(java.time.OffsetDateTime.now());
        images.saveAndFlush(image);
        return Map.of("url", mediaBaseUrl + fsId, "fsId", fsId);
    }

    public ResponseEntity<byte[]> read(String fsId) {
        if (!fsId.matches("\\d{1,20}") || !images.existsById(fsId)) {
            throw new ResourceNotFoundException("error.plan_campaign_not_found");
        }
        ResponseEntity<byte[]> response = http.getForEntity(storageUrl + "/api/v1/media/stream/" + fsId, byte[].class);
        MediaType type = response.getHeaders().getContentType();
        if (type == null || !Set.of("image/png", "image/jpeg", "image/webp").contains(type.toString().split(";")[0])) {
            throw new IllegalStateException("Storage returned an unsupported image");
        }
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(response.getBody());
    }
}
