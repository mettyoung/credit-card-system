package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.application.ApplicationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * FR8.2 / FR8.3: the reviewer's side of the decision. Not part of {@code Applications}, which is the
 * applicant's API and loads everything by owner; a reviewer decides on applications that are not theirs.
 */
@RestController
@RequestMapping(ReviewController.BASE_PATH)
@RequiredArgsConstructor
class ReviewController {

    static final String BASE_PATH = "/v1/review/applications";
    static final String REVIEWER_ID_HEADER = "X-Reviewer-Id";

    private final ApplicationService applications;

    @GetMapping
    List<ReviewQueueItem> referred(@RequestHeader(REVIEWER_ID_HEADER) ReviewerId reviewerId) {
        return applications.referred();
    }

    @PostMapping("/{id}/decision")
    ApplicationResponse decide(@RequestHeader(REVIEWER_ID_HEADER) ReviewerId reviewerId,
                               @PathVariable UUID id,
                               @Valid @RequestBody ReviewRequest request) {
        return applications.review(reviewerId.value(), id, request);
    }
}
