package com.mettyoung.creditcardapplication.application.internal;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * FR11: the development timeline. Not there at all unless the property turns it on, so a production build does
 * not reveal it exists: the stream carries vendor outcomes and referral reasons the applicant must never see.
 */
@RestController
@ConditionalOnProperty(name = "app.ui.timeline.enabled", havingValue = "true")
@RequiredArgsConstructor
class TimelineController {

    private final ApplicationService applications;
    private final TimelineStreams streams;

    /** @param lastEventId the browser's resume point; the audit {@code seq} of the last event it received */
    @GetMapping(path = "/v1/applications/{id}/timeline", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter timeline(@RequestHeader(ApplicationController.USER_ID_HEADER) UserId userId,
                        @PathVariable UUID id,
                        @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId) {
        applications.requireOwned(userId.value(), id);
        return streams.open(id, lastEventId == null ? 0 : lastEventId);
    }
}
