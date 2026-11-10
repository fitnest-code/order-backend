package az.fitnest.order.pricingcampaign.controller;

import az.fitnest.order.exception.UnauthorizedException;
import az.fitnest.order.pricingcampaign.dto.SubscriptionHomeStateDto;
import az.fitnest.order.pricingcampaign.service.PricingCampaignService;
import az.fitnest.order.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/me/subscription-home-state")
@RequiredArgsConstructor
public class SubscriptionHomeStateController {

    private final PricingCampaignService service;

    @GetMapping
    public SubscriptionHomeStateDto get() {
        return service.homeState(requireUser());
    }

    static Long requireUser() {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) throw new UnauthorizedException("error.unauthorized");
        return userId;
    }
}
