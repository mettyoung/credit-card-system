package com.mettyoung.creditcardapplication.vendor.internal;

import java.util.List;
import java.util.Map;

/**
 * Onfido's own vocabulary. Package-private on purpose: the compiler, not a convention, is what keeps vendor
 * DTOs out of the rest of the codebase.
 * <p>
 * Shapes follow Onfido's published API. Only the fields this integration reads are declared; unknown ones are
 * ignored, so the vendor adding a field does not break us.
 */
final class OnfidoDtos {

    private OnfidoDtos() {
    }

    record ApplicantRequest(String first_name, String last_name, String dob, Address address) {
    }

    record Address(String country) {
    }

    record ApplicantResponse(String id) {
    }

    record DocumentResponse(String id) {
    }

    record CheckRequest(String applicant_id, List<String> report_names, List<String> document_ids) {
    }

    /**
     * @param result     {@code clear} / {@code consider} / {@code unidentified} — the verdict
     * @param report_ids the reports that carry the reason; fetched separately
     */
    record CheckResponse(String id, String status, String result, List<String> report_ids) {

        boolean isComplete() {
            return "complete".equals(status);
        }
    }

    /** {@code GET /checks?applicant_id=}: every check the applicant has. */
    record CheckList(List<CheckResponse> checks) {
    }

    /**
     * @param sub_result {@code clear} / {@code caution} / {@code suspected} / {@code rejected} — what turns a
     *                   bare {@code consider} into an actionable answer
     */
    record ReportResponse(String id, String name, String result, String sub_result, Map<String, Object> properties) {
    }

    /** The callback body. A notification: it says an answer exists, never what the answer is. */
    record WebhookEvent(Payload payload) {

        record Payload(String resource_type, String action, Resource object) {
        }

        record Resource(String id) {
        }
    }
}
