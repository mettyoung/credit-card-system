package com.mettyoung.creditcardapplication.vendor.internal;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Records that a vendor said an answer is ready. Deliberately does not interpret the body beyond the two ids
 * it needs, and deliberately does not call the vendor: this runs on the vendor's own request thread.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class VendorInbox {

    public static final String VENDOR_ONFIDO = "onfido";

    private static final Logger log = LoggerFactory.getLogger(VendorInbox.class);

    private final VendorInboxRepository repository;
    private final ObjectMapper json;
    private final Clock clock;


    /** Not transactional: one insert, and a duplicate is an expected outcome rather than a failure. */
    public void accept(byte[] rawBody) {
        Optional<Notification> parsed = parse(rawBody);
        if (parsed.isEmpty()) {
            // Signature checked out but the shape did not. Worth a log line, not a 500: retrying would
            // deliver the same body.
            log.warn("Discarding IDV webhook with an unrecognised shape");
            return;
        }
        Notification notification = parsed.get();
        if (repository.existsByVendorAndEventId(VENDOR_ONFIDO, notification.eventId())) {
            return;
        }
        try {
            repository.save(VendorInboxEvent.received(VENDOR_ONFIDO, notification.eventId(),
                    notification.vendorRef(), clock.instant()));
        } catch (DataIntegrityViolationException e) {
            // Two concurrent deliveries of the same event. The unique index decided; nothing to do.
            log.debug("Duplicate IDV webhook ignored");
        }
    }

    private Optional<Notification> parse(byte[] rawBody) {
        try {
            JsonNode root = json.readTree(rawBody);
            JsonNode payload = root.path("payload");
            String vendorRef = payload.path("object").path("id").asString();
            if (vendorRef == null || vendorRef.isBlank()) {
                return Optional.empty();
            }
            // Onfido does not send an event id, so the resource plus the action is the natural dedupe key:
            // the same completion delivered twice yields the same string.
            String action = payload.path("action").asString();
            String eventId = vendorRef + ":" + (action == null ? "" : action);
            return Optional.of(new Notification(eventId, vendorRef));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private record Notification(String eventId, String vendorRef) {
    }
}
