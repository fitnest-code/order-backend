package az.fitnest.order.dto.homegym;

import java.util.List;

public record HomeGymVisitPage(
        List<HomeGymVisitResponse> items,
        int page,
        int size,
        long total) {
}
