package com.clinicflow.web.error;

import com.clinicflow.domain.port.*;
import com.clinicflow.web.RequestIdFilter;
import com.clinicflow.web.dto.Dto;
import com.clinicflow.web.dto.Mappers;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/**
 * THE exception table (design.md section 4). Deji throws domain exceptions; this is the only place that decides
 * status and error code. The interface switches on `code`, never on `detail`.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---------------------------------------------------------------- domain
    @ExceptionHandler(SlotTakenException.class)
    ResponseEntity<ProblemDetail> slotTaken(SlotTakenException e) {
        ProblemDetail p = problem(HttpStatus.CONFLICT, "That slot has just been taken", "SLOT_TAKEN",
                "Another patient booked this slot a moment before you. Please choose another time.");
        p.setProperty("suggestedSlots", Mappers.slots(e.suggestions()));
        return respond(p);
    }

    @ExceptionHandler(SlotInPastException.class)
    ResponseEntity<ProblemDetail> slotInPast(SlotInPastException e) {
        return respond(problem(HttpStatus.UNPROCESSABLE_ENTITY, "That slot is in the past", "SLOT_IN_PAST",
                "Please choose a slot that has not started yet."));
    }

    @ExceptionHandler(UnknownVisitTypeException.class)
    ResponseEntity<ProblemDetail> unknownVisitType(UnknownVisitTypeException e) {
        return validation(List.of(new Dto.FieldError("visitTypeCode", "Choose one of the listed visit types")));
    }

    @ExceptionHandler(InvalidTransitionException.class)
    ResponseEntity<ProblemDetail> invalidTransition(InvalidTransitionException e, HttpServletRequest req) {
        ProblemDetail p = problem(HttpStatus.CONFLICT, "Not allowed from this status", "INVALID_TRANSITION", e.getMessage());
        if (e.current() != null && isStaffRoute(req)) p.setProperty("current", Mappers.staff(e.current()));
        return respond(p);
    }

    @ExceptionHandler(StaleStateException.class)
    ResponseEntity<ProblemDetail> stale(StaleStateException e, HttpServletRequest req) {
        ProblemDetail p = problem(HttpStatus.CONFLICT, "This appointment has changed", "STALE_STATE",
                "Someone else already changed this appointment. The screen has been refreshed with its current state.");
        if (e.current() != null && isStaffRoute(req)) p.setProperty("current", Mappers.staff(e.current()));
        return respond(p);
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(NotFoundException e) {
        return respond(problem(HttpStatus.NOT_FOUND, "Not found", "NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(BadManageTokenException.class)
    ResponseEntity<ProblemDetail> badToken(BadManageTokenException e) {
        return respond(problem(HttpStatus.UNAUTHORIZED, "Not signed in", "UNAUTHENTICATED",
                "That manage token is not valid for this booking."));
    }

    @ExceptionHandler(ClinicClosedException.class)
    ResponseEntity<ProblemDetail> closed(ClinicClosedException e) {
        return respond(problem(HttpStatus.CONFLICT, "Clinic closed", "CLINIC_CLOSED", e.getMessage()));
    }

    // ---------------------------------------------------------------- api layer
    @ExceptionHandler(BadCredentialsApiException.class)
    ResponseEntity<ProblemDetail> badCredentials(BadCredentialsApiException e) {
        return respond(problem(HttpStatus.UNAUTHORIZED, "Not signed in", "UNAUTHENTICATED", e.getMessage()));
    }

    @ExceptionHandler(IdempotencyKeyMismatchException.class)
    ResponseEntity<ProblemDetail> keyMismatch(IdempotencyKeyMismatchException e) {
        return respond(problem(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency key reused", "IDEMPOTENCY_KEY_MISMATCH",
                e.getMessage() + ". Use a new key for a new submission."));
    }

    @ExceptionHandler(ApiValidationException.class)
    ResponseEntity<ProblemDetail> apiValidation(ApiValidationException e) { return validation(e.fieldErrors()); }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> beanValidation(MethodArgumentNotValidException e) {
        return validation(e.getBindingResult().getFieldErrors().stream()
                .map(f -> new Dto.FieldError(f.getField(), f.getDefaultMessage())).toList());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> typeMismatch(MethodArgumentTypeMismatchException e) {
        return validation(List.of(new Dto.FieldError(e.getName(), "Invalid value")));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ProblemDetail> missingParam(MissingServletRequestParameterException e) {
        return validation(List.of(new Dto.FieldError(e.getParameterName(), "This parameter is required")));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException e) {
        return validation(List.of(new Dto.FieldError("body", "The request body is missing or malformed")));
    }

    /** Everything else: framework errors keep their status; anything unexpected is a 500 with no internals leaked. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> fallback(Exception e) {
        if (e instanceof ErrorResponse er) {
            HttpStatus status = HttpStatus.valueOf(er.getStatusCode().value());
            String code = status == HttpStatus.NOT_FOUND ? "NOT_FOUND" : status.is4xxClientError() ? "VALIDATION_FAILED" : "INTERNAL_ERROR";
            ProblemDetail p = er.getBody();
            p.setProperty("code", code);
            p.setProperty("requestId", MDC.get(RequestIdFilter.MDC_KEY));
            return respond(p);
        }
        log.error("Unhandled exception", e);
        return respond(problem(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong", "INTERNAL_ERROR",
                "Something went wrong on our side. Try again; quote the request id if it keeps happening."));
    }

    // ---------------------------------------------------------------- helpers
    private ResponseEntity<ProblemDetail> validation(List<Dto.FieldError> errors) {
        ProblemDetail p = problem(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed", "VALIDATION_FAILED",
                "Some fields need attention.");
        p.setProperty("fieldErrors", errors);
        return respond(p);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String code, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(title);
        p.setProperty("code", code);
        p.setProperty("requestId", MDC.get(RequestIdFilter.MDC_KEY));
        return p;
    }

    /** Content type is set explicitly so it works even when the caller asked for text/event-stream. */
    private static ResponseEntity<ProblemDetail> respond(ProblemDetail p) {
        return ResponseEntity.status(p.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
    }

    private static boolean isStaffRoute(HttpServletRequest req) {
        return !req.getRequestURI().startsWith("/api/v1/patient/");
    }
}
