package az.fitnest.order.dto.homegym;

import az.fitnest.order.model.enums.HomeGymSource;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PUT /api/v1/subscriptions/{id}/home-gym}.
 * {@code ADMIN} is accepted only on the admin endpoint.
 */
public record HomeGymSelectRequest(
        @NotNull(message = "error.home_gym.gym_id_required") Long gymId,
        @NotNull(message = "error.home_gym.source_required") HomeGymSource source) {
}
