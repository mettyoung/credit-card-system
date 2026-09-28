package com.mettyoung.creditcardapplication.vendor.internal;

import com.mettyoung.creditcardapplication.shared.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * A webhook we have been told about, durably, before doing anything with it. The unique index on
 * (vendor, eventId) is what makes a duplicate delivery a no-op, and writing the row before answering 200 is
 * what makes a crash after the response harmless.
 * <p>
 * The body is not stored as the result — it is only a notification, and the result is always fetched.
 */
@Getter
@Entity
@Table(name = "vendor_inbox")
class VendorInboxEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "vendor", nullable = false, updatable = false)
    private String vendor;

    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "vendor_ref", nullable = false, updatable = false)
    private String vendorRef;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected VendorInboxEvent() {
        // for JPA
    }

    private VendorInboxEvent(String vendor, String eventId, String vendorRef, Instant receivedAt) {
        this.id = UuidV7.generate();
        this.vendor = vendor;
        this.eventId = eventId;
        this.vendorRef = vendorRef;
        this.receivedAt = receivedAt;
    }

    public static VendorInboxEvent received(String vendor, String eventId, String vendorRef, Instant now) {
        return new VendorInboxEvent(vendor, eventId, vendorRef, now);
    }

    public void markProcessed(Instant now) {
        processedAt = now;
    }
}
