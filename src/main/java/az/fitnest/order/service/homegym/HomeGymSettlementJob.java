package az.fitnest.order.service.homegym;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

/**
 * Runs the monthly gym settlement on the 1st at 03:00 (R17).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HomeGymSettlementJob {

    private final HomeGymSettlementService settlementService;

    @Scheduled(cron = "0 0 3 1 * *")
    public void settlePreviousMonth() {
        YearMonth runMonth = YearMonth.now(HomeGymPeriodCalculator.BAKU);
        try {
            settlementService.settleMonth(runMonth);
        } catch (Exception e) {
            log.error("Home-gym settlement failed for runMonth={}", runMonth, e);
        }
    }
}
