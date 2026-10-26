package az.fitnest.order.service;

import az.fitnest.order.client.PlanCampaignDirectoryClient;
import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.exception.ConflictException;
import az.fitnest.order.exception.ResourceNotFoundException;
import az.fitnest.order.model.entity.PlanCampaign;
import az.fitnest.order.repository.PlanCampaignRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
public class PlanCampaignService {
    private final PlanCampaignRepository repository;
    private final PlanCampaignDirectoryClient directory;

    public record MobileItem(UUID id, String code, String locale, PlanCampaign.Content content,
                             List<Long> packageIds, List<Integer> durationMonths,
                             OffsetDateTime endsAt, String placement, String pricingEffect) {}
    public record MobileResponse(int schemaVersion, boolean eligible, List<MobileItem> items) {}
    public record AdminUpdate(long version, String name, boolean enabled, OffsetDateTime startsAt,
                              OffsetDateTime endsAt, List<Long> packageIds, List<Integer> durationMonths,
                              Map<String, PlanCampaign.Content> content) {}

    @Transactional(readOnly = true)
    public MobileResponse forUser(Long userId, Long packageId, String language, OffsetDateTime now) {
        List<PlanCampaign> campaigns = repository.findByEnabledTrueOrderByCodeAsc();
        if (campaigns.isEmpty()) return empty();
        Optional<String> phone = directory.phoneFor(userId);
        if (phone.isEmpty() || !eligiblePhone(phone.get())) return empty();
        List<MobileItem> items = new ArrayList<>();
        for (PlanCampaign campaign : campaigns) {
            if (!"BAKCELL_PLANS".equals(campaign.getCode())) continue;
            if (campaign.getStartsAt() == null || campaign.getEndsAt() == null
                    || now.isBefore(campaign.getStartsAt()) || !now.isBefore(campaign.getEndsAt())) continue;
            List<Long> packages = campaign.getPackageIds() == null ? List.of() : List.of(campaign.getPackageIds());
            if (packageId != null && !packages.isEmpty() && !packages.contains(packageId)) continue;
            Map<String, PlanCampaign.Content> content = campaign.getContent();
            if (content == null) continue;
            String lang = normalizeLanguage(language);
            if (!isComplete(content.get(lang))) lang = "az";
            if (!isComplete(content.get(lang))) continue;
            items.add(new MobileItem(campaign.getId(), campaign.getCode(), lang, content.get(lang), packages,
                    campaign.getDurationMonths() == null ? List.of() : List.of(campaign.getDurationMonths()),
                    campaign.getEndsAt(), "PLANS_TOP_AND_BEFORE_ELIGIBLE_DURATIONS", "NONE"));
        }
        return new MobileResponse(2, !items.isEmpty(), items);
    }

    private MobileResponse empty() { return new MobileResponse(2, false, List.of()); }

    /** Prefix targeting, not an assertion about the subscriber's current mobile carrier. */
    public static boolean eligiblePhone(String phone) {
        if (phone == null) return false;
        String normalized = phone.replaceAll("[\\s().-]", "");
        if (normalized.matches("0(?:55|99)\\d{7}")) normalized = "+994" + normalized.substring(1);
        else if (normalized.matches("(?:55|99)\\d{7}")) normalized = "+994" + normalized;
        else if (normalized.startsWith("00994")) normalized = "+" + normalized.substring(2);
        else if (normalized.startsWith("994")) normalized = "+" + normalized;
        return normalized.matches("\\+994(?:55|99)\\d{7}");
    }

    private static String normalizeLanguage(String language) {
        if (language == null) return "az";
        String lang = language.split(",")[0].split(";")[0].trim().toLowerCase(Locale.ROOT).split("[-_]")[0];
        return Set.of("az", "en", "ru").contains(lang) ? lang : "az";
    }

    private static boolean isComplete(PlanCampaign.Content c) {
        return c != null && nonBlank(c.heading()) && nonBlank(c.subtitle()) && nonBlank(c.specialOffersTitle())
                && https(c.bannerImageUrl()) && nonBlank(c.imageAlt());
    }
    private static boolean nonBlank(String value) { return value != null && !value.isBlank(); }
    private static boolean https(String url) {
        if (!nonBlank(url)) return false;
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException e) { return false; }
    }

    @Transactional(readOnly = true)
    public List<PlanCampaign> list() { return repository.findAllByOrderByCodeAsc(); }
    @Transactional(readOnly = true)
    public PlanCampaign get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("error.plan_campaign_not_found"));
    }

    @Transactional
    public PlanCampaign update(UUID id, AdminUpdate request, Long adminId) {
        if (request == null) invalid();
        PlanCampaign campaign = get(id);
        if (campaign.getVersion() != request.version()) throw new ConflictException("error.version_conflict");
        if (!nonBlank(request.name()) || request.name().length() > 120) invalid();
        if (request.content() == null || !request.content().keySet().stream().allMatch(Set.of("az", "en", "ru")::contains)) invalid();
        if (request.content() != null) request.content().values().forEach(c -> {
            if (c == null) { invalid(); return; }
            bounded(c.heading(), 160); bounded(c.subtitle(), 300); bounded(c.specialOffersTitle(), 80); bounded(c.imageAlt(), 240);
            for (String url : Arrays.asList(c.bannerImageUrl(), c.darkBannerImageUrl(), c.iconUrl(), c.darkIconUrl())) {
                if (nonBlank(url) && (!https(url) || url.length() > 2048)) invalid();
            }
        });
        if (request.enabled() && (request.startsAt() == null || request.endsAt() == null
                || !request.endsAt().isAfter(request.startsAt()) || !isComplete(request.content().get("az")))) invalid();
        if (request.packageIds() != null && request.packageIds().stream().anyMatch(value -> value == null || value <= 0)) invalid();
        if (request.durationMonths() == null || request.durationMonths().isEmpty()
                || request.durationMonths().stream().anyMatch(value -> value == null || value < 1 || value > 120)) invalid();
        campaign.setName(request.name().trim());
        campaign.setEnabled(request.enabled());
        campaign.setStartsAt(request.startsAt()); campaign.setEndsAt(request.endsAt());
        campaign.setPackageIds(request.packageIds() == null ? new Long[0] : request.packageIds().stream().distinct().toArray(Long[]::new));
        campaign.setDurationMonths(request.durationMonths().stream().distinct().sorted().toArray(Integer[]::new));
        campaign.setContent(request.content()); campaign.setUpdatedAt(OffsetDateTime.now()); campaign.setUpdatedBy(adminId);
        return repository.saveAndFlush(campaign);
    }
    private static void bounded(String value, int max) { if (value != null && value.length() > max) invalid(); }
    private static void invalid() { throw new BadRequestException("error.plan_campaign_invalid_content"); }
}
