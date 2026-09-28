package com.mettyoung.creditcardapplication.vendor.internal;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The IDV callback. Authenticated by HMAC, not by {@code X-User-Id} — the caller is a vendor, not an applicant.
 * <p>
 * Answers {@code 200} fast, having done exactly two things: verified the signature and recorded that an answer
 * exists. Fetching the answer is the inbox worker's job, so a slow vendor round trip can never make us time
 * out their delivery and earn a redelivery storm.
 */
@RestController
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class IdvWebhookController {

    static final String SIGNATURE_HEADER = "X-SHA2-Signature";

    private final SignatureVerifier verifier;
    private final VendorInbox inbox;


    /**
     * Takes the body as {@code byte[]}: the signature covers the raw bytes, so binding to a DTO first would
     * destroy the thing being verified.
     */
    @PostMapping(path = "/webhooks/idv")
    ResponseEntity<Void> receive(@RequestBody byte[] rawBody,
                                 @RequestHeader(name = SIGNATURE_HEADER, required = false) String signature) {
        if (!verifier.isValid(rawBody, signature)) {
            // No body, and the log carries no payload: an unauthenticated caller learns nothing.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        inbox.accept(rawBody);
        // 200 even for a duplicate: the vendor did its job, and re-sending would not help either of us.
        return ResponseEntity.ok().build();
    }
}
