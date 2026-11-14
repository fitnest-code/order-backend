package az.fitnest.order.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import az.fitnest.order.exception.PrimaryGymV4ExceptionHandler;
import az.fitnest.order.service.PrimaryGymV4Service;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class PrimaryGymV4ControllerTest {
  @Test
  void internalCheckInRequiresSharedServiceKey() throws Exception {
    var service = mock(PrimaryGymV4Service.class);
    var controller = new PrimaryGymV4Controller(service);
    ReflectionTestUtils.setField(controller, "internalKey", "test-secret");
    var mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new PrimaryGymV4ExceptionHandler())
            .build();
    mvc.perform(
            post("/api/v4/internal/primary-gym/check-ins")
                .contentType("application/json")
                .content("{\"gymId\":4,\"requestId\":\"one\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("INVALID_SERVICE_KEY"));
    verifyNoInteractions(service);
  }

  @Test
  void businessConflictStays409() throws Exception {
    var service = mock(PrimaryGymV4Service.class);
    when(service.status(null))
        .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "NO_ACTIVE_SUBSCRIPTION"));
    var mvc =
        MockMvcBuilders.standaloneSetup(new PrimaryGymV4Controller(service))
            .setControllerAdvice(new PrimaryGymV4ExceptionHandler())
            .build();
    mvc.perform(get("/api/v4/me/subscriptions/primary-gym"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_SUBSCRIPTION"));
  }
}
