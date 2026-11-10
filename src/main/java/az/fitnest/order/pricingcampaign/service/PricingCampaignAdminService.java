package az.fitnest.order.pricingcampaign.service;

import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.exception.ConflictException;
import az.fitnest.order.exception.ResourceNotFoundException;
import az.fitnest.order.pricingcampaign.dto.AdminPricingCampaignRequest;
import az.fitnest.order.pricingcampaign.dto.AdminPricingCampaignRequest.Localized;
import az.fitnest.order.pricingcampaign.dto.AdminPricingCampaignResponse;
import az.fitnest.order.pricingcampaign.dto.PricingDiscountDto;
import az.fitnest.order.pricingcampaign.entity.PricingCampaign;
import az.fitnest.order.pricingcampaign.entity.PricingCampaignDiscount;
import az.fitnest.order.pricingcampaign.entity.PricingCampaignStatus;
import az.fitnest.order.pricingcampaign.repository.PricingCampaignRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Admin CRUD kept apart from the user-facing service so each stays small. */
@Service
@RequiredArgsConstructor
public class PricingCampaignAdminService {

    private final PricingCampaignRepository repository;

    @Transactional(readOnly = true)
    public List<AdminPricingCampaignResponse> list() {
        return repository.findAll().stream().map(PricingCampaignAdminService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public AdminPricingCampaignResponse get(UUID id) {
        return toResponse(find(id));
    }

    @Transactional
    public AdminPricingCampaignResponse create(AdminPricingCampaignRequest req) {
        if (repository.findByCode(req.code()).isPresent()) throw new ConflictException("error.campaign_code_exists");
        PricingCampaign c = new PricingCampaign();
        c.setId(UUID.randomUUID());
        apply(c, req);
        return toResponse(repository.save(c));
    }

    @Transactional
    public AdminPricingCampaignResponse update(UUID id, AdminPricingCampaignRequest req) {
        PricingCampaign c = find(id);
        repository.findByCode(req.code()).filter(o -> !o.getId().equals(id))
                .ifPresent(o -> { throw new ConflictException("error.campaign_code_exists"); });
        apply(c, req);
        return toResponse(repository.save(c));
    }

    @Transactional
    public AdminPricingCampaignResponse activate(UUID id) {
        PricingCampaign c = find(id);
        // Two overlapping ACTIVE campaigns would make "which discount applies" ambiguous.
        boolean overlap = repository.findByStatusAndType(PricingCampaignStatus.ACTIVE, c.getType()).stream()
                .anyMatch(o -> !o.getId().equals(id)
                        && o.getStartsAt().isBefore(c.getEndsAt()) && c.getStartsAt().isBefore(o.getEndsAt()));
        if (overlap) throw new ConflictException("error.campaign_window_overlap");
        c.setStatus(PricingCampaignStatus.ACTIVE);
        return toResponse(repository.save(c));
    }

    @Transactional
    public AdminPricingCampaignResponse disable(UUID id) {
        PricingCampaign c = find(id);
        c.setStatus(PricingCampaignStatus.DISABLED);
        return toResponse(repository.save(c));
    }

    private PricingCampaign find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("error.campaign_not_found"));
    }

    private static void apply(PricingCampaign c, AdminPricingCampaignRequest r) {
        if (!r.endsAt().isAfter(r.startsAt())) throw new BadRequestException("error.campaign_invalid_window");
        validateTiers(r);
        c.setCode(r.code());
        c.setStartsAt(r.startsAt());
        c.setEndsAt(r.endsAt());
        c.setMaxPercent(r.maxPercent());
        setCopy(r.pushTitle(), c::setPushTitleAz, c::setPushTitleRu, c::setPushTitleEn);
        setCopy(r.pushBody(), c::setPushBodyAz, c::setPushBodyRu, c::setPushBodyEn);
        setCopy(r.pushLast12hTitle(), c::setPushLast12hTitleAz, c::setPushLast12hTitleRu, c::setPushLast12hTitleEn);
        setCopy(r.pushLast12hBody(), c::setPushLast12hBodyAz, c::setPushLast12hBodyRu, c::setPushLast12hBodyEn);
        // orphanRemoval handles the old tiers; clear+add keeps the managed collection instance.
        c.getDiscounts().clear();
        r.discounts().forEach(d -> {
            PricingCampaignDiscount e = new PricingCampaignDiscount();
            e.setCampaign(c);
            e.setMonths(d.months());
            e.setPercent(d.percent());
            c.getDiscounts().add(e);
        });
    }

    private static void validateTiers(AdminPricingCampaignRequest r) {
        long distinct = r.discounts().stream().map(PricingDiscountDto::months).filter(Objects::nonNull).distinct().count();
        boolean bad = distinct != r.discounts().size() || r.discounts().stream().anyMatch(d ->
                d.months() == null || d.months() < 2 || d.percent() == null
                        || d.percent().signum() < 0 || d.percent().compareTo(BigDecimal.valueOf(r.maxPercent())) > 0);
        if (bad) throw new BadRequestException("error.campaign_invalid_discounts");
    }

    private static void setCopy(Localized l, java.util.function.Consumer<String> az,
                                java.util.function.Consumer<String> ru, java.util.function.Consumer<String> en) {
        if (l == null) return;
        az.accept(l.az());
        ru.accept(l.ru());
        en.accept(l.en());
    }

    static AdminPricingCampaignResponse toResponse(PricingCampaign c) {
        return new AdminPricingCampaignResponse(c.getId(), c.getCode(), c.getType(), c.getStatus().name(),
                c.getStartsAt(), c.getEndsAt(), c.getMaxPercent(),
                c.getDiscounts().stream().map(d -> new PricingDiscountDto(d.getMonths(), d.getPercent())).toList(),
                new Localized(c.getPushTitleAz(), c.getPushTitleRu(), c.getPushTitleEn()),
                new Localized(c.getPushBodyAz(), c.getPushBodyRu(), c.getPushBodyEn()),
                new Localized(c.getPushLast12hTitleAz(), c.getPushLast12hTitleRu(), c.getPushLast12hTitleEn()),
                new Localized(c.getPushLast12hBodyAz(), c.getPushLast12hBodyRu(), c.getPushLast12hBodyEn()),
                c.getCreatedAt(), c.getUpdatedAt());
    }
}
