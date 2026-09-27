package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.shared.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.util.List;

/**
 * Single place where errors become RFC 9457 problem responses. Domain exceptions are mapped from what they
 * carry (code, details), so only request-shape problems need a case of their own.
 */
final class ProblemMapper {

    record FieldError(String field, String message) {
    }

    private ProblemMapper() {
    }

    static ResponseEntity<ProblemDetail> problem(HttpStatus status, DomainException e) {
        ProblemDetail problem = problem(status, e.code(), e.getMessage());
        e.details().forEach(problem::setProperty);
        return respond(problem);
    }

    static ResponseEntity<ProblemDetail> notFound() {
        return respond(problem(HttpStatus.NOT_FOUND, "not-found", "Application not found."));
    }

    static ResponseEntity<ProblemDetail> invalidRequest(String detail, List<FieldError> errors) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-request", detail);
        problem.setProperty("errors", errors);
        return respond(problem);
    }

    static ResponseEntity<ProblemDetail> unauthorized() {
        return respond(problem(HttpStatus.UNAUTHORIZED, "unauthorized",
                "The " + ApplicationController.USER_ID_HEADER + " header is required."));
    }

    private static ProblemDetail problem(HttpStatus status, String type, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("/problems/" + type));
        return problem;
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
