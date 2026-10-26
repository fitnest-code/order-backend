package az.fitnest.order.service;

import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.exception.ResourceNotFoundException;
import az.fitnest.order.repository.PlanCampaignImageRepository;
import static org.mockito.Mockito.*;
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
    private final PlanCampaignImageRepository images = mock(PlanCampaignImageRepository.class);
    @Test void uploadReturnsEnvironmentSpecificUrl() throws Exception {
        PlanCampaignImageService service = new PlanCampaignImageService("http://storage:8080", "", "development", images);
        RestTemplate http = (RestTemplate) ReflectionTestUtils.getField(service, "http");
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("http://storage:8080/api/v1/files/upload"))
                .andExpect(header("X-User-Id", "7"))
                .andRespond(withSuccess("{\"data\":{\"fsId\":12345,\"name\":\"banner.png\"}}", MediaType.APPLICATION_JSON));
        var image = new MockMultipartFile("file", "banner.png", "image/png", new byte[]{1, 2, 3});
        assertEquals("https://api-dev.fitnest.az/api/v2/me/plan-campaigns/images/12345", service.upload(image, 7L).get("url"));
        verify(images).saveAndFlush(any());
        server.verify();
    }
    @Test void rejectsUnsupportedFileTypesBeforeStorage() {
        PlanCampaignImageService service = new PlanCampaignImageService("http://storage:8080", "", "production", images);
        var file = new MockMultipartFile("file", "script.svg", "image/svg+xml", new byte[]{1});
        assertThrows(BadRequestException.class, () -> service.upload(file, 7L));
    }
    @Test void rejectsOversizedFileBeforeStorage() {
        PlanCampaignImageService service = new PlanCampaignImageService("http://storage:8080", "", "production", images);
        var file = new MockMultipartFile("file", "big.png", "image/png", new byte[8 * 1024 * 1024 + 1]);
        assertThrows(BadRequestException.class, () -> service.upload(file, 7L));
    }
    @Test void cannotReadArbitraryStorageImages() {
        PlanCampaignImageService service = new PlanCampaignImageService("http://storage:8080", "", "production", images);
        assertThrows(ResourceNotFoundException.class, () -> service.read("12345"));
    }
    @Test void registeredImageStreamsWithOriginalType() {
        PlanCampaignImageService service = new PlanCampaignImageService("http://storage:8080", "", "production", images);
        when(images.existsById("12345")).thenReturn(true);
        MockRestServiceServer server = MockRestServiceServer.bindTo((RestTemplate) ReflectionTestUtils.getField(service, "http")).build();
        server.expect(requestTo("http://storage:8080/api/v1/media/stream/12345"))
                .andRespond(withSuccess(new byte[]{1,2,3}, MediaType.IMAGE_PNG));
        var response = service.read("12345");
        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertArrayEquals(new byte[]{1,2,3}, response.getBody());
        server.verify();
    }
}
