package az.fitnest.order.controller;

import az.fitnest.order.model.entity.PlanCampaign;
import az.fitnest.order.service.PlanCampaignService;
import az.fitnest.order.service.PlanCampaignImageService;
import az.fitnest.order.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v2/admin/plan-campaigns")
@PreAuthorize("hasRole('ADMIN')")
public class PlanCampaignAdminController {
    private final PlanCampaignService service;
    private final PlanCampaignImageService imageService;
    @PostMapping(value = "/images", consumes = "multipart/form-data")
    public java.util.Map<String, String> upload(@RequestPart("file") org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        return imageService.upload(file, UserContext.getCurrentUserId());
    }
    @GetMapping public List<PlanCampaign> list() { return service.list(); }
    @GetMapping("/{id}") public PlanCampaign get(@PathVariable UUID id) { return service.get(id); }
    @PutMapping("/{id}") public PlanCampaign update(@PathVariable UUID id, @RequestBody PlanCampaignService.AdminUpdate request) {
        return service.update(id, request, UserContext.getCurrentUserId());
    }
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.CONFLICT)
    public java.util.Map<String, Object> concurrentUpdate() {
        return java.util.Map.of("error", java.util.Map.of("code", "VERSION_CONFLICT", "message", "Campaign changed. Reload before saving."));
    }
}
