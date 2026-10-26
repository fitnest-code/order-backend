package az.fitnest.order.service;

import az.fitnest.order.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PlanCampaignImageServiceTest {
    @Test void uploadReturnsEnvironmentSpecificUrl() throws Exception {
        PlanCampaignImageService service = new PlanCampaignImageService("http://storage:8080", "", "development");
        RestTemplate http = (RestTemplate) ReflectionTestUtils.getField(service, "http");
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("http://storage:8080/api/v1/files/upload"))
                .andExpect(header("X-User-Id", "7"))
                .andRespond(withSuccess("{\"data\":{\"fsId\":12345,\"name\":\"banner.png\"}}", MediaType.APPLICATION_JSON));
        var image = new MockMultipartFile("file", "banner.png", "image/png", new byte[]{1, 2, 3});
        assertEquals("https://api-dev.fitnest.az/api/v1/media/stream/12345", service.upload(image, 7L).get("url"));
        server.verify();
    }
    @Test void rejectsUnsupportedFileTypesBeforeStorage() {
        PlanCampaignImageService service = new PlanCampaignImageService("http://storage:8080", "", "production");
        var file = new MockMultipartFile("file", "script.svg", "image/svg+xml", new byte[]{1});
        assertThrows(BadRequestException.class, () -> service.upload(file, 7L));
    }
    @Test void rejectsOversizedFileBeforeStorage() {
        PlanCampaignImageService service = new PlanCampaignImageService("http://storage:8080", "", "production");
        var file = new MockMultipartFile("file", "big.png", "image/png", new byte[8 * 1024 * 1024 + 1]);
        assertThrows(BadRequestException.class, () -> service.upload(file, 7L));
    }
}
