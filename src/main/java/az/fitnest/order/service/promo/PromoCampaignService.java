package az.fitnest.order.service.promo;

import az.fitnest.order.dto.promo.*;
import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.exception.ConflictException;
import az.fitnest.order.exception.ResourceNotFoundException;
import az.fitnest.order.model.entity.promo.*;
import az.fitnest.order.repository.promo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PromoCampaignService {

    private final PromoCampaignRepository campaignRepository;
    private final PromoPartnerRepository partnerRepository;
    private final PromoCampaignTranslationRepository translationRepository;
    private final PromoCampaignPopupRepository popupRepository;
    private final PromoCampaignMediaRepository mediaRepository;
    private final PromoAudienceNumberRepository audienceNumberRepository;
    private final PromoGrantRepository grantRepository;
    private final PromoAudienceUploadRepository uploadRepository;
    private final PromoPhoneHasher phoneHasher;
    private final PromoAuditLogService auditLogService;

    @Transactional
    public AdminPromoCampaignResponse create(AdminPromoCampaignRequest request, Long adminId) {
        String codeNormalized = request.getCode().toUpperCase();
        if (campaignRepository.findByCodeNormalized(codeNormalized).isPresent()) {
            throw new BadRequestException("error.promo_code_taken");
        }
        if (request.getPartnerId() != null) {
            PromoPartner partner = partnerRepository.findById(request.getPartnerId())
                    .orElseThrow(() -> new ResourceNotFoundException("error.partner_not_found"));
            if (partner.getStatus() == PromoPartnerStatus.INACTIVE) {
                throw new BadRequestException("error.partner_inactive");
            }
        }
        validateDiscount(request);

        PromoCampaign campaign = new PromoCampaign();
        mapRequestToEntity(request, campaign);
        campaign.setCodeNormalized(codeNormalized);
        campaign.setCreatedBy(adminId);
        campaign.setRedeemedCount(0);
        campaign.setStatus(PromoCampaignStatus.DRAFT);
        campaign.setVersion(0);
        PromoCampaign saved = campaignRepository.save(campaign);

        saveTranslations(saved.getId(), request.getTranslations());
        savePopup(saved.getId(), request.getPopup());
        saveMedia(saved.getId(), request.getMedia());
        log.info("Created promo campaign {} by admin {}", saved.getId(), adminId);
        return getDetail(saved.getId());
    }

    @Transactional
    public AdminPromoCampaignResponse update(UUID id, AdminPromoCampaignRequest request, Long adminId) {
        PromoCampaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("error.promo_campaign_not_found"));
        if (request.getVersion() != null && !request.getVersion().equals(campaign.getVersion())) {
            throw new ConflictException("error.version_conflict");
        }
        if (campaign.getStatus() == PromoCampaignStatus.ACTIVE || campaign.getStatus() == PromoCampaignStatus.ENDED) {
            // Only allow editing end date, total limit, texts, image on active
            campaign.setEndsAt(request.getEndsAt());
            if (request.getTotalLimit() != null) campaign.setTotalLimit(request.getTotalLimit());
        } else {
            String codeNormalized = request.getCode().toUpperCase();
            if (!codeNormalized.equals(campaign.getCodeNormalized())
                    && campaignRepository.findByCodeNormalized(codeNormalized).isPresent()) {
                throw new BadRequestException("error.promo_code_taken");
            }
            validateDiscount(request);
            mapRequestToEntity(request, campaign);
            campaign.setCodeNormalized(codeNormalized);
        }
        campaignRepository.save(campaign);
        if (request.getTranslations() != null) {
            translationRepository.deleteByCampaignId(id);
            saveTranslations(id, request.getTranslations());
        }
        if (request.getPopup() != null) {
            popupRepository.deleteByCampaignId(id);
            savePopup(id, request.getPopup());
        }
        if (request.getMedia() != null) {
            mediaRepository.deleteByCampaignId(id);
            saveMedia(id, request.getMedia());
        }
        log.info("Updated promo campaign {} by admin {}", id, adminId);
        return getDetail(id);
    }

    @Transactional(readOnly = true)
    public AdminPromoCampaignResponse getDetail(UUID id) {
        PromoCampaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("error.promo_campaign_not_found"));
        return mapToResponse(campaign);
    }

    @Transactional(readOnly = true)
    public Page<AdminPromoCampaignListItemResponse> list(String search, List<String> statuses, UUID partnerId,
                                                         OffsetDateTime from, OffsetDateTime to, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        List<PromoCampaignStatus> st = (statuses == null || statuses.isEmpty())
                ? List.of(PromoCampaignStatus.DRAFT, PromoCampaignStatus.SCHEDULED,
                        PromoCampaignStatus.ACTIVE, PromoCampaignStatus.PAUSED, PromoCampaignStatus.ENDED)
                : statuses.stream().map(PromoCampaignStatus::valueOf).collect(Collectors.toList());

        String like = (search != null && !search.isBlank()) ? "%" + search.toLowerCase() + "%" : null;

        List<PromoCampaign> campaigns;
        long total;
        if (partnerId != null) {
            campaigns = campaignRepository.searchCampaigns(like, st, partnerId, from, to, pageable);
            total = campaignRepository.countSearchCampaigns(like, st, partnerId, from, to);
        } else {
            campaigns = campaignRepository.searchCampaigns(like, st, null, from, to, pageable);
            total = campaignRepository.countSearchCampaigns(like, st, null, from, to);
        }
        List<AdminPromoCampaignListItemResponse> items;
        if (campaigns.isEmpty()) {
            items = List.of();
        } else {
            // Batch preloads: 1 partner query + 1 grouped count query for the whole
            // page instead of 2 queries per row (N+1, 13.2).
            List<UUID> ids = campaigns.stream().map(PromoCampaign::getId).collect(Collectors.toList());
            Map<UUID, String> partnerNames = new HashMap<>();
            List<UUID> partnerIds = campaigns.stream().map(PromoCampaign::getPartnerId)
                    .filter(Objects::nonNull).distinct().collect(Collectors.toList());
            if (!partnerIds.isEmpty()) {
                for (PromoPartner p : partnerRepository.findAllById(partnerIds)) {
                    partnerNames.put(p.getId(), p.getName());
                }
            }
            Map<UUID, Long> eligibleCounts = new HashMap<>();
            for (Object[] row : audienceNumberRepository.countByCampaignIds(ids)) {
                eligibleCounts.put((UUID) row[0], (Long) row[1]);
            }
            items = campaigns.stream()
                    .map(c -> mapToListItem(c, partnerNames.get(c.getPartnerId()),
                            eligibleCounts.getOrDefault(c.getId(), 0L)))
                    .collect(Collectors.toList());
        }
        return new org.springframework.data.domain.PageImpl<>(items, pageable, total);
    }

    @Transactional
    public void delete(UUID id, Long adminId) {
        PromoCampaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("error.promo_campaign_not_found"));
        if (campaign.getStatus() != PromoCampaignStatus.DRAFT) {
            throw new ConflictException("error.promo_invalid_status_transition");
        }
        campaignRepository.delete(campaign);
        auditLogService.log(adminId, "DELETE", "PROMO_CAMPAIGN", id,
                "{\"status\":\"DRAFT\"}", "{\"deleted\":true}", null, null);
    }

    @Transactional
    public AdminPromoCampaignResponse duplicate(UUID id, Long adminId) {
        PromoCampaign source = campaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("error.promo_campaign_not_found"));
        AdminPromoCampaignRequest req = new AdminPromoCampaignRequest();
        req.setName(source.getName() + " copy");
        String newCode = generateCopyCode(source.getCodeNormalized());
        req.setCode(newCode);
        req.setDiscountType(source.getDiscountType().name());
        req.setDiscountValue(source.getDiscountValue());
        req.setMaxDiscountAmount(source.getMaxDiscountAmount());
        req.setAudienceType(source.getAudienceType().name());
        req.setAllowedPrefixes(source.getAllowedPrefixes() != null ? List.of(source.getAllowedPrefixes()) : null);
        req.setPlanIds(source.getPlanIds() != null ? List.of(source.getPlanIds()) : null);
        req.setTierCodes(source.getTierCodes() != null ? List.of(source.getTierCodes()) : null);
        req.setStartsAt(source.getStartsAt());
        req.setEndsAt(source.getEndsAt());
        req.setUsesPerUser(source.getUsesPerUser());
        req.setTotalLimit(source.getTotalLimit());
        req.setPriority(source.getPriority());
        req.setFirstPaymentOnly(source.getFirstPaymentOnly());
        req.setAllowFitcoin(source.getAllowFitcoin());
        req.setAllowInstallments(source.getAllowInstallments());
        req.setIncludeExistingUsers(source.getIncludeExistingUsers());
        req.setTranslations(translationRepository.findByCampaignId(id).stream().map(t -> AdminPromoCampaignTranslationRequest.builder()
                .locale(t.getLocale().name())
                .popupTitle(t.getPopupTitle())
                .popupBody(t.getPopupBody())
                .ctaLabel(t.getCtaLabel())
                .cardLabel(t.getCardLabel())
                .notEligibleText(t.getNotEligibleText())
                .couponLabel(t.getCouponLabel())
                .build()).collect(Collectors.toList()));
        popupRepository.findByCampaignId(id).ifPresent(p -> req.setPopup(AdminPromoCampaignPopupRequest.builder()
                .ctaTarget(p.getCtaTarget().name())
                .planId(p.getPlanId())
                .url(p.getUrl())
                .imageUrl(p.getImageUrl())
                .brandColorOverride(source.getBrandColorOverride())
                .logoUrlOverride(source.getLogoUrlOverride())
                .logoOnDarkUrlOverride(source.getLogoOnDarkUrlOverride())
                .durationSec(p.getDurationSec())
                .build()));
        req.setMedia(mediaRepository.findByCampaignId(id).stream()
                .map(m -> AdminPromoCampaignMediaRequest.builder()
                        .locale(m.getLocale().name())
                        .bannerImageUrl(m.getBannerImageUrl())
                        .heroImageUrl(m.getHeroImageUrl())
                        .build())
                .collect(Collectors.toList()));
        req.setPartnerId(source.getPartnerId());
        return create(req, adminId);
    }

    private String generateCopyCode(String original) {
        String base = original;
        if (base.length() > 14) base = base.substring(0, 14);
        String code = base + UUID.randomUUID().toString().substring(0, 2);
        if (code.length() > 16) code = code.substring(0, 16);
        return code;
    }

    @Transactional
    public AdminPromoCampaignResponse patch(UUID id, Map<String, Object> updates, Long adminId) {
        PromoCampaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("error.promo_campaign_not_found"));
        Integer reqVersion = updates.get("version") != null ? ((Number) updates.get("version")).intValue() : null;
        if (reqVersion != null && !reqVersion.equals(campaign.getVersion())) {
            throw new ConflictException("error.version_conflict");
        }
        // Apply allowed partial updates
        if (updates.containsKey("name")) campaign.setName((String) updates.get("name"));
        if (updates.containsKey("endsAt")) campaign.setEndsAt(OffsetDateTime.parse((String) updates.get("endsAt")));
        if (updates.containsKey("startsAt")) campaign.setStartsAt(OffsetDateTime.parse((String) updates.get("startsAt")));
        if (updates.containsKey("totalLimit")) campaign.setTotalLimit(updates.get("totalLimit") != null ? ((Number) updates.get("totalLimit")).intValue() : null);
        if (updates.containsKey("usesPerUser")) campaign.setUsesPerUser(((Number) updates.get("usesPerUser")).intValue());
        if (updates.containsKey("priority")) campaign.setPriority(((Number) updates.get("priority")).intValue());
        if (updates.containsKey("firstPaymentOnly")) campaign.setFirstPaymentOnly((Boolean) updates.get("firstPaymentOnly"));
        if (updates.containsKey("allowFitcoin")) campaign.setAllowFitcoin((Boolean) updates.get("allowFitcoin"));
        if (updates.containsKey("allowInstallments")) campaign.setAllowInstallments((Boolean) updates.get("allowInstallments"));
        if (updates.containsKey("brandColorOverride")) campaign.setBrandColorOverride((String) updates.get("brandColorOverride"));
        if (updates.containsKey("logoUrlOverride")) campaign.setLogoUrlOverride((String) updates.get("logoUrlOverride"));
        if (updates.containsKey("logoOnDarkUrlOverride")) campaign.setLogoOnDarkUrlOverride((String) updates.get("logoOnDarkUrlOverride"));
        if (updates.containsKey("maxDiscountAmount")) {
            Object v = updates.get("maxDiscountAmount");
            campaign.setMaxDiscountAmount(v != null ? new BigDecimal(v.toString()) : null);
        }
        if (updates.containsKey("code")) {
            String code = (String) updates.get("code");
            String norm = code.toUpperCase();
            if (!norm.equals(campaign.getCodeNormalized()) && campaignRepository.findByCodeNormalized(norm).isPresent()) {
                throw new BadRequestException("error.promo_code_taken");
            }
            campaign.setCode(code);
            campaign.setCodeNormalized(norm);
        }
        campaignRepository.save(campaign);
        return getDetail(id);
    }

    // Status transitions
    @Transactional
    public AdminPromoCampaignResponse publish(UUID id, Boolean schedule, Long adminId) {
        PromoCampaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("error.promo_campaign_not_found"));
        if (campaign.getStatus() != PromoCampaignStatus.DRAFT) {
            throw new ConflictException("error.promo_invalid_status_transition");
        }
        AdminPromoCampaignPublishCheckResponse check = publishCheck(id);
        if (!check.getCanPublish()) {
            throw new ConflictException("error.promo_publish_blocked");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (Boolean.TRUE.equals(schedule) || campaign.getStartsAt().isAfter(now)) {
            campaign.setStatus(PromoCampaignStatus.SCHEDULED);
        } else {
            campaign.setStatus(PromoCampaignStatus.ACTIVE);
        }
        campaignRepository.save(campaign);
        auditLogService.log(adminId, "PUBLISH", "PROMO_CAMPAIGN", id,
                null, "{\"status\":\"" + campaign.getStatus() + "\"}", null, null);
        log.info("Published promo campaign {} by admin {}", id, adminId);
        return getDetail(id);
    }

    @Transactional
    public AdminPromoCampaignResponse unschedule(UUID id, Long adminId) {
        PromoCampaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("error.promo_campaign_not_found"));
        if (campaign.getStatus() != PromoCampaignStatus.SCHEDULED) {
            throw new ConflictException("error.promo_invalid_status_transition");
        }
        campaign.setStatus(PromoCampaignStatus.DRAFT);
        campaignRepository.save(campaign);
        auditLogService.log(adminId, "UNSCHEDULE", "PROMO_CAMPAIGN", id,
                "{\"status\":\"SCHEDULED\"}", "{\"status\":\"DRAFT\"}", null, null);
        return getDetail(id);
    }

    @Transactional
    public AdminPromoCampaignResponse pause(UUID id, Long adminId) {
        PromoCampaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("error.promo_campaign_not_found"));
        if (campaign.getStatus() != PromoCampaignStatus.ACTIVE) {
            throw new ConflictException("error.promo_invalid_status_transition");
        }
        campaign.setStatus(PromoCampaignStatus.PAUSED);
        campaignRepository.save(campaign);
        auditLogService.log(adminId, "PAUSE", "PROMO_CAMPAIGN", id,
                "{\"status\":\"ACTIVE\"}", "{\"status\":\"PAUSED\"}", null, null);
        return getDetail(id);
    }

    @Transactional
    public AdminPromoCampaignResponse resume(UUID id, Long adminId) {
        PromoCampaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("error.promo_campaign_not_found"));
        if (campaign.getStatus() != PromoCampaignStatus.PAUSED) {
            throw new ConflictException("error.promo_invalid_status_transition");
        }
        if (campaign.getEndsAt().isBefore(OffsetDateTime.now())) {
            throw new ConflictException("error.promo_invalid_status_transition");
        }
        campaign.setStatus(PromoCampaignStatus.ACTIVE);
        campaignRepository.save(campaign);
        auditLogService.log(adminId, "RESUME", "PROMO_CAMPAIGN", id,
                "{\"status\":\"PAUSED\"}", "{\"status\":\"ACTIVE\"}", null, null);
        return getDetail(id);
    }

    @Transactional
    public AdminPromoCampaignResponse end(UUID id, Long adminId) {
        PromoCampaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("error.promo_campaign_not_found"));
        if (campaign.getStatus() != PromoCampaignStatus.ACTIVE
                && campaign.getStatus() != PromoCampaignStatus.PAUSED
                && campaign.getStatus() != PromoCampaignStatus.SCHEDULED) {
            throw new ConflictException("error.promo_invalid_status_transition");
        }
        String before = campaign.getStatus().name();
        campaign.setStatus(PromoCampaignStatus.ENDED);
        campaignRepository.save(campaign);
        auditLogService.log(adminId, "END", "PROMO_CAMPAIGN", id,
                "{\"status\":\"" + before + "\"}", "{\"status\":\"ENDED\"}", null, null);
        return getDetail(id);
    }

    @Transactional(readOnly = true)
    public AdminPromoCampaignPublishCheckResponse publishCheck(UUID id) {
        PromoCampaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("error.promo_campaign_not_found"));
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        boolean hasAz = translationRepository.findByCampaignId(id).stream()
                .anyMatch(t -> t.getLocale() == PromoLocale.az
                        && t.getPopupTitle() != null && !t.getPopupTitle().isBlank()
                        && t.getPopupBody() != null && !t.getPopupBody().isBlank());
        if (!hasAz) blockers.add("az popup title/body missing");

        if (campaign.getCode() == null || campaign.getCode().isBlank()) blockers.add("code missing");

        if (campaign.getPlanIds() == null || campaign.getPlanIds().length == 0) {
            blockers.add("no plans selected");
        }

        if (campaign.getEndsAt() == null || campaign.getStartsAt() == null) {
            blockers.add("period missing");
        }

        long validNumbers = 0L;
        long existingUsers = 0L;
        if (campaign.getAudienceType() == PromoAudienceType.UPLOADED_LIST) {
            validNumbers = audienceNumberRepository.countByCampaignId(id);
            if (validNumbers == 0) {
                blockers.add("No valid numbers uploaded");
            }
            existingUsers = grantRepository.countByCampaignIdAndStatus(id, PromoGrantStatus.GRANTED)
                    + grantRepository.countByCampaignIdAndStatus(id, PromoGrantStatus.RESERVED)
                    + grantRepository.countByCampaignIdAndStatus(id, PromoGrantStatus.REDEEMED);
            existingUsers = grantRepository.findByCampaignId(id, Pageable.unpaged()).getTotalElements();
        } else {
            existingUsers = 0L;
        }

        if (campaign.getDiscountValue() != null && campaign.getDiscountType() == PromoDiscountType.FIXED) {
            // fixed: value is in AZN; warn if extremely high
        }

        return AdminPromoCampaignPublishCheckResponse.builder()
                .canPublish(blockers.isEmpty())
                .blockers(blockers)
                .warnings(warnings)
                .eligibleUsers(validNumbers)
                .existingUsersWithPopup(existingUsers)
                .build();
    }

    @Transactional(readOnly = true)
    public AdminPromoCodeAvailabilityResponse checkCodeAvailability(String code) {
        String norm = code != null ? code.toUpperCase() : "";
        boolean taken = campaignRepository.findByCodeNormalized(norm).isPresent();
        return AdminPromoCodeAvailabilityResponse.builder()
                .available(!taken)
                .message(taken ? "This code is already used" : null)
                .build();
    }

    // stats / numbers / export
    @Transactional(readOnly = true)
    public AdminPromoCampaignSummaryResponse getSummary() {
        int activeCampaigns = campaignRepository.findByStatus(PromoCampaignStatus.ACTIVE).size();
        List<PromoCampaign> all = campaignRepository.findAll();
        long eligible = 0L;
        for (PromoCampaign c : all) {
            if (c.getAudienceType() == PromoAudienceType.UPLOADED_LIST) {
                eligible += audienceNumberRepository.countByCampaignId(c.getId());
            }
        }
        OffsetDateTime now = OffsetDateTime.now();
        long redeemed30 = 0L;
        BigDecimal discount30 = BigDecimal.ZERO;
        for (PromoCampaign c : all) {
            for (PromoGrant g : grantRepository.findByCampaignIdAndStatus(c.getId(), PromoGrantStatus.REDEEMED)) {
                if (g.getRedeemedAt() != null && g.getRedeemedAt().isAfter(now.minusDays(30))) {
                    redeemed30++;
                    if (g.getDiscountAmount() != null) discount30 = discount30.add(g.getDiscountAmount());
                }
            }
        }
        return AdminPromoCampaignSummaryResponse.builder()
                .activeCampaigns(activeCampaigns)
                .eligibleNumbers(eligible)
                .redeemedLast30Days(redeemed30)
                .discountGivenLast30Days(discount30)
                .build();
    }

    @Transactional(readOnly = true)
    public AdminPromoCampaignStatsResponse getStats(UUID id) {
        PromoCampaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("error.promo_campaign_not_found"));
        long eligible = campaign.getAudienceType() == PromoAudienceType.UPLOADED_LIST
                ? audienceNumberRepository.countByCampaignId(id) : 0L;
        long redeemed = grantRepository.countByCampaignIdAndStatus(id, PromoGrantStatus.REDEEMED);
        long popupSeen = grantRepository.countPopupSeenByCampaignId(id, PromoPopupStatus.SEEN);
        long registered = grantRepository.findByCampaignId(id, Pageable.unpaged()).getTotalElements();
        long reserved = grantRepository.countByCampaignIdAndStatus(id, PromoGrantStatus.RESERVED);
        BigDecimal discountGiven = grantRepository.sumDiscountAmountByCampaignIdAndStatus(id, PromoGrantStatus.REDEEMED);
        if (discountGiven == null) discountGiven = BigDecimal.ZERO;
        BigDecimal avg = redeemed > 0 ? discountGiven.divide(BigDecimal.valueOf(redeemed), 2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO;
        return AdminPromoCampaignStatsResponse.builder()
                .eligibleNumbers(eligible)
                .registeredUsers(registered)
                .redeemedCount(redeemed)
                .popupSeen(popupSeen)
                .discountGiven(discountGiven)
                .avgDiscountPerOrder(avg)
                .updatedAt(OffsetDateTime.now())
                .funnel(AdminPromoCampaignFunnelResponse.builder()
                        .eligible(eligible)
                        .registered(registered)
                        .sawPopup(popupSeen)
                        .selectedCode(reserved + redeemed)
                        .paid(redeemed)
                        .build())
                .build();
    }

    @Transactional(readOnly = true)
    public List<AdminPromoCampaignNumberItemResponse> exportNumbers(UUID id) {
        campaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("error.promo_campaign_not_found"));
        List<PromoGrant> grants = grantRepository.findByCampaignId(id, Pageable.unpaged()).getContent();
        return grants.stream().map(g -> AdminPromoCampaignNumberItemResponse.builder()
                .phoneHash(g.getPhoneHash())
                .status(g.getStatus().name())
                .registered(true)
                .sawPopup(g.getPopupStatus() == PromoPopupStatus.SEEN)
                .redeemedAt(g.getRedeemedAt())
                .userId(g.getUserId())
                .build()).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<PromoGrant> exportGrants(UUID id) {
        campaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("error.promo_campaign_not_found"));
        return grantRepository.findByCampaignId(id, Pageable.unpaged()).getContent();
    }

    // helpers
    private void validateDiscount(AdminPromoCampaignRequest request) {
        BigDecimal dv = request.getDiscountValue();
        if (dv == null) throw new BadRequestException("error.promo_invalid_discount");
        String type = request.getDiscountType();
        if ("PERCENT".equalsIgnoreCase(type)) {
            if (dv.compareTo(BigDecimal.ONE) < 0 || dv.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new BadRequestException("error.promo_invalid_discount_percent");
            }
        } else if ("FIXED".equalsIgnoreCase(type)) {
            if (dv.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BadRequestException("error.promo_invalid_discount_fixed");
            }
        } else {
            throw new BadRequestException("error.promo_invalid_discount_type");
        }
    }

    private void mapRequestToEntity(AdminPromoCampaignRequest request, PromoCampaign campaign) {
        campaign.setPartnerId(request.getPartnerId());
        campaign.setName(request.getName());
        campaign.setCode(request.getCode());
        campaign.setDiscountType(PromoDiscountType.valueOf(request.getDiscountType().toUpperCase()));
        campaign.setDiscountValue(request.getDiscountValue());
        campaign.setMaxDiscountAmount(request.getMaxDiscountAmount());
        campaign.setAudienceType(PromoAudienceType.valueOf(request.getAudienceType().toUpperCase()));
        campaign.setAllowedPrefixes(request.getAllowedPrefixes() != null ? request.getAllowedPrefixes().toArray(new String[0]) : new String[0]);
        campaign.setPlanIds(request.getPlanIds() != null ? request.getPlanIds().toArray(new String[0]) : new String[0]);
        campaign.setTierCodes(request.getTierCodes() != null ? request.getTierCodes().toArray(new String[0]) : new String[0]);
        campaign.setStartsAt(request.getStartsAt());
        campaign.setEndsAt(request.getEndsAt());
        campaign.setUsesPerUser(request.getUsesPerUser() != null ? request.getUsesPerUser() : 1);
        campaign.setTotalLimit(request.getTotalLimit());
        campaign.setPriority(request.getPriority() != null ? request.getPriority() : 0);
        campaign.setFirstPaymentOnly(request.getFirstPaymentOnly() != null ? request.getFirstPaymentOnly() : true);
        campaign.setAllowFitcoin(request.getAllowFitcoin() != null ? request.getAllowFitcoin() : true);
        campaign.setAllowInstallments(request.getAllowInstallments() != null ? request.getAllowInstallments() : true);
        campaign.setIncludeExistingUsers(request.getIncludeExistingUsers() != null ? request.getIncludeExistingUsers() : true);
        campaign.setBrandColorOverride(request.getPopup() != null ? request.getPopup().getBrandColorOverride() : null);
        campaign.setLogoUrlOverride(request.getPopup() != null ? request.getPopup().getLogoUrlOverride() : null);
        campaign.setLogoOnDarkUrlOverride(request.getPopup() != null ? request.getPopup().getLogoOnDarkUrlOverride() : null);
    }

    private void saveTranslations(UUID campaignId, List<AdminPromoCampaignTranslationRequest> translations) {
        if (translations == null || translations.isEmpty()) {
            throw new BadRequestException("error.promo_az_translation_required");
        }
        for (AdminPromoCampaignTranslationRequest t : translations) {
            PromoCampaignTranslation entity = new PromoCampaignTranslation();
            entity.setCampaignId(campaignId);
            entity.setLocale(PromoLocale.valueOf(t.getLocale()));
            entity.setPopupTitle(t.getPopupTitle());
            entity.setPopupBody(t.getPopupBody());
            entity.setCtaLabel(t.getCtaLabel() != null ? t.getCtaLabel() : "Abunelik sec");
            entity.setCardLabel(t.getCardLabel());
            entity.setNotEligibleText(t.getNotEligibleText());
            entity.setCouponLabel(t.getCouponLabel());
            translationRepository.save(entity);
        }
    }

    private void savePopup(UUID campaignId, AdminPromoCampaignPopupRequest popup) {
        if (popup == null) return;
        PromoCampaignPopup entity = new PromoCampaignPopup();
        entity.setCampaignId(campaignId);
        entity.setCtaTarget(PromoCtaTarget.valueOf(popup.getCtaTarget().toUpperCase()));
        entity.setPlanId(popup.getPlanId());
        entity.setUrl(popup.getUrl());
        entity.setImageUrl(popup.getImageUrl());
        entity.setDurationSec(popup.getDurationSec() != null ? popup.getDurationSec() : 7);
        popupRepository.save(entity);
    }

    private void saveMedia(UUID campaignId, List<AdminPromoCampaignMediaRequest> media) {
        if (media == null || media.isEmpty()) return;
        for (AdminPromoCampaignMediaRequest m : media) {
            PromoCampaignMedia entity = new PromoCampaignMedia();
            entity.setCampaignId(campaignId);
            entity.setLocale(PromoLocale.valueOf(m.getLocale()));
            entity.setBannerImageUrl(m.getBannerImageUrl());
            entity.setHeroImageUrl(m.getHeroImageUrl());
            mediaRepository.save(entity);
        }
    }

    private AdminPromoCampaignResponse mapToResponse(PromoCampaign c) {
        List<AdminPromoCampaignTranslationResponse> translations = translationRepository.findByCampaignId(c.getId()).stream()
                .map(t -> AdminPromoCampaignTranslationResponse.builder()
                        .locale(t.getLocale().name())
                        .popupTitle(t.getPopupTitle())
                        .popupBody(t.getPopupBody())
                        .ctaLabel(t.getCtaLabel())
                        .cardLabel(t.getCardLabel())
                        .notEligibleText(t.getNotEligibleText())
                        .couponLabel(t.getCouponLabel())
                        .build())
                .collect(Collectors.toList());

        AdminPromoCampaignPopupResponse popupResp = popupRepository.findByCampaignId(c.getId())
                .map(p -> AdminPromoCampaignPopupResponse.builder()
                        .ctaTarget(p.getCtaTarget().name())
                        .planId(p.getPlanId())
                        .url(p.getUrl())
                        .imageUrl(p.getImageUrl())
                        .brandColorOverride(c.getBrandColorOverride())
                        .logoUrlOverride(c.getLogoUrlOverride())
                        .logoOnDarkUrlOverride(c.getLogoOnDarkUrlOverride())
                        .durationSec(p.getDurationSec())
                        .build())
                .orElse(null);

        String partnerName = null;
        if (c.getPartnerId() != null) {
            partnerName = partnerRepository.findById(c.getPartnerId()).map(PromoPartner::getName).orElse(null);
        }

        return AdminPromoCampaignResponse.builder()
                .id(c.getId())
                .partnerId(c.getPartnerId())
                .partnerName(partnerName)
                .name(c.getName())
                .code(c.getCode())
                .discountType(c.getDiscountType().name())
                .discountValue(c.getDiscountValue())
                .maxDiscountAmount(c.getMaxDiscountAmount())
                .audienceType(c.getAudienceType().name())
                .allowedPrefixes(c.getAllowedPrefixes() != null ? List.of(c.getAllowedPrefixes()) : null)
                .planIds(c.getPlanIds() != null ? List.of(c.getPlanIds()) : null)
                .tierCodes(c.getTierCodes() != null ? List.of(c.getTierCodes()) : null)
                .startsAt(c.getStartsAt())
                .endsAt(c.getEndsAt())
                .usesPerUser(c.getUsesPerUser())
                .totalLimit(c.getTotalLimit())
                .redeemedCount(c.getRedeemedCount())
                .priority(c.getPriority())
                .firstPaymentOnly(c.getFirstPaymentOnly())
                .allowFitcoin(c.getAllowFitcoin())
                .allowInstallments(c.getAllowInstallments())
                .includeExistingUsers(c.getIncludeExistingUsers())
                .brandColorOverride(c.getBrandColorOverride())
                .logoUrlOverride(c.getLogoUrlOverride())
                .logoOnDarkUrlOverride(c.getLogoOnDarkUrlOverride())
                .status(c.getStatus().name())
                .createdBy(c.getCreatedBy())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .version(c.getVersion())
                .translations(translations)
                .popup(popupResp)
                .media(mediaRepository.findByCampaignId(c.getId()).stream()
                        .map(m -> AdminPromoCampaignMediaResponse.builder()
                                .locale(m.getLocale().name())
                                .bannerImageUrl(m.getBannerImageUrl())
                                .heroImageUrl(m.getHeroImageUrl())
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }

    private AdminPromoCampaignListItemResponse mapToListItem(PromoCampaign c, String partnerName, Long eligibleCount) {
        Long eligible = c.getAudienceType() == PromoAudienceType.UPLOADED_LIST ? eligibleCount : null;
        return AdminPromoCampaignListItemResponse.builder()
                .id(c.getId())
                .name(c.getName())
                .code(c.getCode())
                .discountType(c.getDiscountType().name())
                .discountValue(c.getDiscountValue())
                .maxDiscountAmount(c.getMaxDiscountAmount())
                .audienceType(c.getAudienceType().name())
                .startsAt(c.getStartsAt())
                .endsAt(c.getEndsAt())
                .redeemedCount(c.getRedeemedCount())
                .totalLimit(c.getTotalLimit())
                .status(c.getStatus().name())
                .priority(c.getPriority())
                .partnerName(partnerName)
                .partnerId(c.getPartnerId())
                .createdAt(c.getCreatedAt())
                .eligibleNumbers(eligible)
                .build();
    }
}
