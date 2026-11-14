package az.fitnest.order.pricingcampaign.controller;

import az.fitnest.order.pricingcampaign.dto.AdminPricingCampaignRequest;
import az.fitnest.order.pricingcampaign.dto.AdminPricingCampaignResponse;
import az.fitnest.order.pricingcampaign.service.PricingCampaignAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2/admin/pricing-campaigns")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class PricingCampaignAdminController {

    private final PricingCampaignAdminService service;

    @GetMapping
    public List<AdminPricingCampaignResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public AdminPricingCampaignResponse get(@PathVariable("id") UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminPricingCampaignResponse create(@Valid @RequestBody AdminPricingCampaignRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public AdminPricingCampaignResponse update(@PathVariable("id") UUID id, @Valid @RequestBody AdminPricingCampaignRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/activate")
    public AdminPricingCampaignResponse activate(@PathVariable("id") UUID id) {
        return service.activate(id);
    }

    @PostMapping("/{id}/disable")
    public AdminPricingCampaignResponse disable(@PathVariable("id") UUID id) {
        return service.disable(id);
    }
}
