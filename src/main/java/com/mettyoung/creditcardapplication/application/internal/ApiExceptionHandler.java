package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.application.internal.ProblemMapper.FieldError;
import com.mettyoung.creditcardapplication.shared.DomainException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Request-shape problems (headers, JSON, path/query parameters) and {@link DomainException}s thrown by the
 * domain. Business errors are mapped in the controller.
 * Details never echo request values, which may contain PII.
 * <p>
 * Highest precedence: with spring.mvc.problemdetails.enabled, Boot registers its own advice that answers
 * these same exceptions with 400 and would otherwise win.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiExceptionHandler {

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ProblemDetail> missingHeader(MissingRequestHeaderException e) {
        if (ApplicationController.USER_ID_HEADER.equalsIgnoreCase(e.getHeaderName())
                || ReviewController.REVIEWER_ID_HEADER.equalsIgnoreCase(e.getHeaderName())) {
            return ProblemMapper.unauthorized();
        }
        return ProblemMapper.invalidRequest("Missing header " + e.getHeaderName() + ".", List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalidBody(MethodArgumentNotValidException e) {
        List<FieldError> errors = e.getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), message(error)))
                .toList();
        return ProblemMapper.invalidRequest("Request validation failed.", errors);
    }

    /**
     * A body field typed as a value object validates while Jackson is binding it, so the domain's own refusal
     * arrives wrapped in this. Unwrapping it keeps the answer identical whether the rule was reached during
     * binding or later in a service — the client should not be able to tell which.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadableBody(HttpMessageNotReadableException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof DomainException domain) {
                return domain(domain);
            }
        }
        return ProblemMapper.invalidRequest("Malformed request body.", fieldErrors(e.getCause()));
    }

    /**
     * One case per category, not per exception: the problem type comes from {@code code()} and the extra
     * members from {@code details()}. A new category fails compilation until it is mapped here.
     * <p>
     * An invalid value answers in the same shape as a request-shape failure — {@code errors: [{field, message}]},
     * built from {@code details()}, whose keys are the rejected fields — so a client reads every 422 the same way.
     */
    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> domain(DomainException e) {
        return switch (e.category) {
            case INVALID_VALUE -> ProblemMapper.invalidRequest(e.getMessage(), fieldErrors(e.details()));
            case CONFLICTING_STATE -> ProblemMapper.problem(HttpStatus.CONFLICT, e);
            case NOT_FOUND -> ProblemMapper.problem(HttpStatus.NOT_FOUND, e);
        };
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> typeMismatch(MethodArgumentTypeMismatchException e) {
        // A malformed id can't identify an existing application.
        if (e.getParameter().hasParameterAnnotation(PathVariable.class)) {
            return ProblemMapper.notFound();
        }
        return ProblemMapper.invalidRequest("Invalid value for " + e.getName() + ".",
                List.of(new FieldError(e.getName(), "Invalid value.")));
    }

    private static List<FieldError> fieldErrors(Map<String, Object> details) {
        return details.entrySet().stream()
                .map(detail -> new FieldError(detail.getKey(), String.valueOf(detail.getValue())))
                .toList();
    }

    private static String message(org.springframework.validation.FieldError error) {
        if ("NotNull".equals(error.getCode())) {
            return "Required.";
        }
        return error.getDefaultMessage() == null ? "Invalid value." : error.getDefaultMessage();
    }

    private static List<FieldError> fieldErrors(Throwable cause) {
        return switch (cause) {
            case UnrecognizedPropertyException unknown ->
                    List.of(new FieldError(unknown.getPropertyName(), "Unknown field."));
            case MismatchedInputException mismatch when !mismatch.getPath().isEmpty() ->
                    List.of(new FieldError(path(mismatch), "Invalid value."));
            case null, default -> List.of();
        };
    }

    private static String path(JacksonException e) {
        return e.getPath().stream()
                .map(ref -> ref.getPropertyName() != null ? ref.getPropertyName() : "[" + ref.getIndex() + "]")
                .collect(Collectors.joining("."));
    }
}
