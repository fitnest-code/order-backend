package az.fitnest.order.service;

import az.fitnest.order.dto.CampaignDetailDto;
import az.fitnest.order.dto.CampaignPushRequest;
import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.exception.CampaignNotAvailableException;
import az.fitnest.order.exception.CampaignPushFailedException;
import az.fitnest.order.exception.ResourceNotFoundException;
import az.fitnest.order.grpc.NotificationGrpcClient;
import az.fitnest.order.model.entity.Campaign;
import az.fitnest.order.model.entity.CampaignStatus;
import az.fitnest.order.repository.CampaignRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read and announce a bonus campaign by id. v1 detail stays available for ended
 * campaigns; this version is what a push tap loads, including a cold start.
 */
@Service
@RequiredArgsConstructor
public class CampaignV2Service {

    private static final List<String> REQUIRED_LANGUAGES = List.of("AZ", "EN", "RU");

    private final CampaignRepository campaignRepository;
    private final CampaignEligibilityService campaignEligibilityService;
    private final NotificationGrpcClient notificationGrpcClient;

    public CampaignDetailDto getAvailableDetail(Long id, String language) {
        Campaign campaign = requireCampaign(id);
        if (!isCurrentlyLive(campaign, campaignEligibilityService.getBakuNow())) {
            throw new CampaignNotAvailableException();
        }
        return campaignEligibilityService.getCampaignDetail(id, language);
    }

    public int push(Long id, CampaignPushRequest request) {
        Campaign campaign = requireCampaign(id);
        if (!isCurrentlyLive(campaign, campaignEligibilityService.getBakuNow())) {
            throw new CampaignNotAvailableException();
        }

        Map<String, NotificationGrpcClient.TitleBody> contents = requireLanguages(request);
        String campaignId = id.toString();
        Map<String, String> data = Map.of(
                "type", "CAMPAIGN",
                "targetType", "CAMPAIGN",
                "targetId", campaignId,
                "deeplink", "fitnest://campaigns/" + campaignId);

        int targetUsers = notificationGrpcClient.broadcastLocalizedPush(contents, data);
        if (targetUsers < 0) {
            throw new CampaignPushFailedException();
        }
        return targetUsers;
    }

    static boolean isCurrentlyLive(Campaign campaign, LocalDateTime now) {
        return campaign.getStatus() == CampaignStatus.ACTIVE
                && campaign.getStartAt() != null
                && campaign.getEndAt() != null
                && now != null
                && !campaign.getStartAt().isAfter(now)
                && !campaign.getEndAt().isBefore(now);
    }

    private Campaign requireCampaign(Long id) {
        return campaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found"));
    }

    private static Map<String, NotificationGrpcClient.TitleBody> requireLanguages(CampaignPushRequest request) {
        Map<String, CampaignPushRequest.LocalizedText> byLanguage = new LinkedHashMap<>();
        if (request != null && request.getContents() != null) {
            request.getContents().forEach((language, text) -> {
                if (language != null && text != null) {
                    byLanguage.put(language.trim().toUpperCase(Locale.ROOT), text);
                }
            });
        }
        Map<String, NotificationGrpcClient.TitleBody> contents = new LinkedHashMap<>();
        for (String language : REQUIRED_LANGUAGES) {
            CampaignPushRequest.LocalizedText text = byLanguage.get(language);
            if (text == null || isBlank(text.getTitle()) || isBlank(text.getBody())) {
                throw new BadRequestException("Campaign push requires title and body in az, en, and ru");
            }
            contents.put(language, new NotificationGrpcClient.TitleBody(text.getTitle().trim(), text.getBody().trim()));
        }
        return contents;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
