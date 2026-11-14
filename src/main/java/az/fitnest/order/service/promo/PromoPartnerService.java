package az.fitnest.order.service.promo;

import az.fitnest.order.dto.promo.AdminPromoPartnerRequest;
import az.fitnest.order.dto.promo.AdminPromoPartnerResponse;
import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.exception.ResourceNotFoundException;
import az.fitnest.order.model.entity.promo.PromoCampaign;
import az.fitnest.order.model.entity.promo.PromoCampaignStatus;
import az.fitnest.order.model.entity.promo.PromoGrantStatus;
import az.fitnest.order.model.entity.promo.PromoPartner;
import az.fitnest.order.model.entity.promo.PromoPartnerStatus;
import az.fitnest.order.repository.promo.PromoPartnerRepository;
import az.fitnest.order.repository.promo.PromoCampaignRepository;
import az.fitnest.order.repository.promo.PromoGrantRepository;
import az.fitnest.order.repository.promo.PromoAudienceNumberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PromoPartnerService {

    private final PromoPartnerRepository partnerRepository;
    private final PromoCampaignRepository campaignRepository;
    private final PromoGrantRepository grantRepository;
    private final PromoAudienceNumberRepository audienceNumberRepository;

    @Transactional
    public AdminPromoPartnerResponse createPartner(AdminPromoPartnerRequest request, Long adminId) {
        if (partnerRepository.existsByName(request.getName())) {
            throw new BadRequestException("error.partner_name_exists");
        }

        PromoPartner partner = new PromoPartner();
        partner.setName(request.getName());
        partner.setLogoUrl(request.getLogoUrl());
        partner.setLogoOnDarkUrl(request.getLogoOnDarkUrl());
        partner.setBrandColor(request.getBrandColor());
        partner.setContactName(request.getContactName());
        partner.setContactEmail(request.getContactEmail());
        partner.setAgreementRef(request.getAgreementRef());
        partner.setRetentionDays(request.getRetentionDays() != null ? request.getRetentionDays() : 90);
        partner.setDefaultPrefixes(request.getDefaultPrefixes() != null ?
                request.getDefaultPrefixes().toArray(new String[0]) : new String[0]);
        partner.setStatus(request.getStatus() != null && request.getStatus() ? PromoPartnerStatus.ACTIVE : PromoPartnerStatus.INACTIVE);

        PromoPartner saved = partnerRepository.save(partner);
        log.info("Created partner {} by admin {}", saved.getId(), adminId);
        return mapToResponse(saved);
    }

    @Transactional
    public AdminPromoPartnerResponse updatePartner(UUID partnerId, AdminPromoPartnerRequest request, Long adminId) {
        PromoPartner partner = partnerRepository.findById(partnerId)
                .orElseThrow(() -> new ResourceNotFoundException("error.partner_not_found"));

        if (!partner.getName().equals(request.getName()) && partnerRepository.existsByName(request.getName())) {
            throw new BadRequestException("error.partner_name_exists");
        }

        partner.setName(request.getName());
        partner.setLogoUrl(request.getLogoUrl());
        partner.setLogoOnDarkUrl(request.getLogoOnDarkUrl());
        partner.setBrandColor(request.getBrandColor());
        partner.setContactName(request.getContactName());
        partner.setContactEmail(request.getContactEmail());
        partner.setAgreementRef(request.getAgreementRef());
        partner.setRetentionDays(request.getRetentionDays() != null ? request.getRetentionDays() : 90);
        partner.setDefaultPrefixes(request.getDefaultPrefixes() != null ?
                request.getDefaultPrefixes().toArray(new String[0]) : new String[0]);
        partner.setStatus(request.getStatus() != null && request.getStatus() ? PromoPartnerStatus.ACTIVE : PromoPartnerStatus.INACTIVE);

        PromoPartner saved = partnerRepository.save(partner);
        log.info("Updated partner {} by admin {}", saved.getId(), adminId);
        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public AdminPromoPartnerResponse getPartner(UUID partnerId) {
        PromoPartner partner = partnerRepository.findById(partnerId)
                .orElseThrow(() -> new ResourceNotFoundException("error.partner_not_found"));
        return mapToResponseWithStats(partner);
    }

    @Transactional(readOnly = true)
    public Page<AdminPromoPartnerResponse> getPartners(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("name"));
        return partnerRepository.findAll(pageable).map(this::mapToResponseWithStats);
    }

    @Transactional
    public void deactivatePartner(UUID partnerId, Long adminId) {
        PromoPartner partner = partnerRepository.findById(partnerId)
                .orElseThrow(() -> new ResourceNotFoundException("error.partner_not_found"));
        partner.setStatus(PromoPartnerStatus.INACTIVE);
        partnerRepository.save(partner);
        log.info("Deactivated partner {} by admin {}", partnerId, adminId);
    }

    @Transactional
    public void activatePartner(UUID partnerId, Long adminId) {
        PromoPartner partner = partnerRepository.findById(partnerId)
                .orElseThrow(() -> new ResourceNotFoundException("error.partner_not_found"));
        partner.setStatus(PromoPartnerStatus.ACTIVE);
        partnerRepository.save(partner);
        log.info("Activated partner {} by admin {}", partnerId, adminId);
    }

    @Transactional(readOnly = true)
    public AdminPromoPartnerResponse getPartnerStats(UUID partnerId) {
        PromoPartner partner = partnerRepository.findById(partnerId)
                .orElseThrow(() -> new ResourceNotFoundException("error.partner_not_found"));
        return mapToResponseWithStats(partner);
    }

    private AdminPromoPartnerResponse mapToResponse(PromoPartner partner) {
        return AdminPromoPartnerResponse.builder()
                .id(partner.getId())
                .name(partner.getName())
                .logoUrl(partner.getLogoUrl())
                .logoOnDarkUrl(partner.getLogoOnDarkUrl())
                .brandColor(partner.getBrandColor())
                .contactName(partner.getContactName())
                .contactEmail(partner.getContactEmail())
                .agreementRef(partner.getAgreementRef())
                .retentionDays(partner.getRetentionDays())
                .defaultPrefixes(partner.getDefaultPrefixes() != null ?
                        List.of(partner.getDefaultPrefixes()) : List.of())
                .status(partner.getStatus().name())
                .createdAt(partner.getCreatedAt())
                .updatedAt(partner.getUpdatedAt())
                .build();
    }

    private AdminPromoPartnerResponse mapToResponseWithStats(PromoPartner partner) {
        AdminPromoPartnerResponse response = mapToResponse(partner);

        List<PromoCampaign> campaigns = campaignRepository.findByPartnerId(partner.getId());
        long activeCampaigns = campaigns.stream()
                .filter(c -> c.getStatus() == PromoCampaignStatus.ACTIVE)
                .count();

        long totalCampaigns = campaigns.size();
        long eligibleNumbers = 0;
        long redeemedCount = 0;

        for (PromoCampaign campaign : campaigns) {
            eligibleNumbers += audienceNumberRepository.countByCampaignId(campaign.getId());
            redeemedCount += grantRepository.countByCampaignIdAndStatus(campaign.getId(),
                    PromoGrantStatus.REDEEMED);
        }

        response.setActiveCampaigns((int) activeCampaigns);
        response.setTotalCampaigns((int) totalCampaigns);
        response.setEligibleNumbers(eligibleNumbers);
        response.setRedeemedCount(redeemedCount);

        return response;
    }
}