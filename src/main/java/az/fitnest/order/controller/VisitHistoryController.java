package az.fitnest.order.controller;

import az.fitnest.order.dto.visithistory.VisitHistoryQuery;
import az.fitnest.order.dto.visithistory.VisitHistoryResponse;
import az.fitnest.order.exception.UnauthorizedException;
import az.fitnest.order.exception.VisitHistoryException;
import az.fitnest.order.model.enums.VisitHistoryMode;
import az.fitnest.order.model.enums.VisitHistoryPreset;
import az.fitnest.order.model.enums.VisitHistoryStatus;
import az.fitnest.order.service.visithistory.VisitHistoryService;
import az.fitnest.order.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * QR visit history of the authenticated subscriber (spec "A. Tarixçə API").
 *
 * <p>This is a new endpoint: the legacy {@code /subscriptions/{id}/home-gym/history}
 * stays untouched and is now expressible as {@code mode=HOME_GYM} here.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/me/visits")
public class VisitHistoryController {

    private final VisitHistoryService visitHistoryService;

    @GetMapping("/history")
    public ResponseEntity<VisitHistoryResponse> getHistory(
            @RequestParam(required = false) String preset,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String gymId,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String limit) {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new UnauthorizedException("error.unauthorized");
        }
        VisitHistoryQuery query = new VisitHistoryQuery(
                parseEnum(VisitHistoryPreset.class, preset, VisitHistoryPreset.LAST_30_DAYS),
                parseDate(from),
                parseDate(to),
                parseEnum(VisitHistoryStatus.class, status, VisitHistoryStatus.ALL),
                parseLong(gymId),
                parseEnum(VisitHistoryMode.class, mode, VisitHistoryMode.ALL),
                cursor,
                parseLimit(limit));
        return ResponseEntity.ok(visitHistoryService.getHistory(userId, query));
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new VisitHistoryException("INVALID_REQUEST", "error.invalid_request");
        }
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new VisitHistoryException("INVALID_DATE_RANGE", "error.invalid_date_range");
        }
    }

    private Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new VisitHistoryException("INVALID_REQUEST", "error.invalid_request");
        }
    }

    private int parseLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return 20;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new VisitHistoryException("INVALID_REQUEST", "error.invalid_request");
        }
    }
}
