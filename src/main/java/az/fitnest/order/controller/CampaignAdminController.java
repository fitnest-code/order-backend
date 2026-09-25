package az.fitnest.order.controller;

import az.fitnest.order.dto.CampaignDetailDto;
import az.fitnest.order.dto.admin.AdminCampaignOfferRequest;
import az.fitnest.order.dto.admin.AdminCampaignRequest;
import az.fitnest.order.dto.admin.AdminCampaignTermRequest;
import az.fitnest.order.model.entity.*;
import az.fitnest.order.repository.*;
import az.fitnest.order.service.CampaignEligibilityService;
import az.fitnest.order.service.TranslationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/campaigns")
@RequiredArgsConstructor
@Slf4j
public class CampaignAdminController {

    private final CampaignRepository campaignRepository;
    private final CampaignOfferRepository campaignOfferRepository;
    private final CampaignTermRepository campaignTermRepository;
    private final CampaignEligibilityService campaignEligibilityService;
    private final TranslationService translationService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<CampaignDetailDto> createCampaign(@Valid @RequestBody AdminCampaignRequest request) {
        Campaign campaign = new Campaign();
        campaign.setCode(request.getCode());
        campaign.setStatus(request.getStatus());
        campaign.setStartAt(request.getStartAt());
        campaign.setEndAt(request.getEndAt());
        campaign.setBannerImageUrl(request.getBannerImageUrl());
        if (request.getCtaTarget() != null) campaign.setCtaTarget(request.getCtaTarget());
        if (request.getOnePerUser() != null) campaign.setOnePerUser(request.getOnePerUser());

        Campaign saved = campaignRepository.save(campaign);
        saveTranslations(saved.getId(), request);

        return ResponseEntity.ok(campaignEligibilityService.getCampaignDetail(saved.getId(), "az"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<CampaignDetailDto> updateCampaign(@PathVariable("id") Long id, @Valid @RequestBody AdminCampaignRequest request) {
        Campaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found with id: " + id));

        campaign.setCode(request.getCode());
        campaign.setStatus(request.getStatus());
        campaign.setStartAt(request.getStartAt());
        campaign.setEndAt(request.getEndAt());
        campaign.setBannerImageUrl(request.getBannerImageUrl());
        if (request.getCtaTarget() != null) campaign.setCtaTarget(request.getCtaTarget());
        if (request.getOnePerUser() != null) campaign.setOnePerUser(request.getOnePerUser());

        Campaign saved = campaignRepository.save(campaign);
        saveTranslations(saved.getId(), request);

        return ResponseEntity.ok(campaignEligibilityService.getCampaignDetail(saved.getId(), "az"));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<CampaignDetailDto> updateStatus(@PathVariable("id") Long id, @RequestParam("status") CampaignStatus status) {
        Campaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found with id: " + id));
        campaign.setStatus(status);
        campaignRepository.save(campaign);
        return ResponseEntity.ok(campaignEligibilityService.getCampaignDetail(id, "az"));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<CampaignDetailDto>> getAllCampaigns() {
        List<Campaign> campaigns = campaignRepository.findAll();
        List<CampaignDetailDto> dtos = campaigns.stream()
                .map(c -> campaignEligibilityService.getCampaignDetail(c.getId(), "az"))
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CampaignDetailDto> getCampaign(@PathVariable("id") Long id) {
        return ResponseEntity.ok(campaignEligibilityService.getCampaignDetail(id, "az"));
    }

    @PutMapping("/{id}/offers")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<CampaignDetailDto> updateOffers(@PathVariable("id") Long id, @Valid @RequestBody List<AdminCampaignOfferRequest> offers) {
        Campaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found with id: " + id));

        campaignOfferRepository.deleteByCampaignId(campaign.getId());

        for (AdminCampaignOfferRequest oReq : offers) {
            if (oReq.getBaseDurationMonths() == null || !List.of(3, 6, 12).contains(oReq.getBaseDurationMonths())) {
                throw new az.fitnest.order.exception.BadRequestException("error.invalid_base_duration_months");
            }
            int expectedBonus = oReq.getBaseDurationMonths() == 3 ? 1 : (oReq.getBaseDurationMonths() == 6 ? 2 : 3);
            if (oReq.getBonusMonths() == null || oReq.getBonusMonths() != expectedBonus) {
                throw new az.fitnest.order.exception.BadRequestException("error.invalid_bonus_months");
            }

            CampaignOffer offer = new CampaignOffer();
            offer.setCampaignId(campaign.getId());
            offer.setBaseDurationMonths(oReq.getBaseDurationMonths());
            offer.setBonusMonths(oReq.getBonusMonths());
            CampaignOffer savedOffer = campaignOfferRepository.save(offer);

            if (oReq.getLabel() != null && !oReq.getLabel().isBlank()) {
                translationService.autoTranslateAndSave("CAMPAIGNOFFER", savedOffer.getId().toString(), "label", oReq.getLabel());
            }
        }

        return ResponseEntity.ok(campaignEligibilityService.getCampaignDetail(id, "az"));
    }

    @PutMapping("/{id}/terms")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<CampaignDetailDto> updateTerms(@PathVariable("id") Long id, @Valid @RequestBody List<AdminCampaignTermRequest> terms) {
        Campaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found with id: " + id));

        campaignTermRepository.deleteByCampaignId(campaign.getId());

        for (AdminCampaignTermRequest tReq : terms) {
            CampaignTerm term = new CampaignTerm();
            term.setCampaignId(campaign.getId());
            term.setSortOrder(tReq.getSortOrder());
            term.setIsPositive(tReq.getIsPositive());
            CampaignTerm savedTerm = campaignTermRepository.save(term);

            if (tReq.getText() != null && !tReq.getText().isBlank()) {
                translationService.autoTranslateAndSave("CAMPAIGNTERM", savedTerm.getId().toString(), "text", tReq.getText());
            }
        }

        return ResponseEntity.ok(campaignEligibilityService.getCampaignDetail(id, "az"));
    }

    private void saveTranslations(Long campaignId, AdminCampaignRequest request) {
        String idStr = campaignId.toString();
        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            translationService.autoTranslateAndSave("CAMPAIGN", idStr, "title", request.getTitle());
        }
        if (request.getDescription() != null && !request.getDescription().isBlank()) {
            translationService.autoTranslateAndSave("CAMPAIGN", idStr, "description", request.getDescription());
        }
        if (request.getCtaLabel() != null && !request.getCtaLabel().isBlank()) {
            translationService.autoTranslateAndSave("CAMPAIGN", idStr, "ctaLabel", request.getCtaLabel());
        }
    }
}
