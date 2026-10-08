package com.mettyoung.creditcardapplication.vendor.internal;

import com.mettyoung.creditcardapplication.document.Documents;
import com.mettyoung.creditcardapplication.vendor.ApplicantSubjects;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Onfido, over HTTP. The only class that knows Onfido's endpoints, vocabulary or error codes.
 * <p>
 * Three calls make one check: create an applicant, upload the document, create the check. Only the third is
 * billed. Onfido does not honour an idempotency key on it, so a retry that cannot rule out an earlier success -
 * a read timeout, a lost reply, a worker that died mid-call - first lists the applicant's checks and adopts one
 * it finds rather than paying for another. That is why the applicant id is handed back to be stored before the
 * check is created.
 */
@Component
class OnfidoIdvAdapter implements IdvPort {

    private static final String DOCUMENT_REPORT = "document";

    private final RestClient client;
    private final Documents documents;
    private final ObjectMapper json;
    private final OnfidoProperties properties;

    OnfidoIdvAdapter(Documents documents, ObjectMapper json, OnfidoProperties properties) {
        this.documents = documents;
        this.json = json;
        this.properties = properties;

        // The request factory is built here rather than injected, so the configured submit timeout is
        // actually applied. A client with no read timeout waits on a hung vendor until the lease expires,
        // which turns one slow call into a worker thread held for a minute.
        // HTTP/1.1, explicitly. The JDK client defaults to HTTP/2 and attempts an h2c upgrade on plaintext,
        // which many servers and proxies answer by cancelling the stream (RST_STREAM) - a failure that looks
        // like an unreachable vendor rather than a protocol disagreement. Negotiating up is not worth it for
        // a handful of small requests.
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                        .version(java.net.http.HttpClient.Version.HTTP_1_1)
                        .connectTimeout(properties.submitTimeout())
                        .build());
        requestFactory.setReadTimeout(properties.submitTimeout());

        this.client = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(properties.baseUrl() + "/" + properties.apiVersion())
                // Onfido's scheme, not a bearer token.
                .defaultHeader("Authorization", "Token token=" + properties.apiToken())
                .build();
    }

    @Override
    public VendorResult<IdvOutcome> submit(ApplicantSubjects.Subject subject, VendorSubjectRef registered,
                                           Consumer<VendorSubjectRef> onRegistered, UUID documentId,
                                           IdempotencyKey key) {
        try {
            // Empty when the document is not accepted. I11 lives in the document module, so the adapter
            // cannot forget it. Not retryable - the document will not become valid by asking again.
            Documents.Content content = documents.contentFor(documentId).orElse(null);
            if (content == null) {
                return failed(new VendorFailure.InvalidRequest("document-not-uploaded"), null);
            }

            String applicantId;
            if (registered == null) {
                applicantId = createApplicant(subject);
                if (applicantId == null) {
                    return failed(new VendorFailure.InvalidRequest("empty-applicant-response"), null);
                }
                // A fresh applicant has no checks, so there is nothing to look for on this attempt.
                onRegistered.accept(new VendorSubjectRef(applicantId));
            } else {
                applicantId = registered.value();
                // An earlier attempt may have created the check and lost the reply. A list is not billed and a
                // check is, so look before creating another.
                Optional<OnfidoDtos.CheckResponse> earlier = existingCheck(applicantId);
                if (earlier.isPresent()) {
                    return resultOf(earlier.get(), true);
                }
            }
            String onfidoDocumentId = uploadDocument(applicantId, content);
            return createCheck(applicantId, onfidoDocumentId, key);
        } catch (ResourceAccessException e) {
            return failed(fromTransport(e), describe(e));
        } catch (org.springframework.web.client.RestClientResponseException e) {
            return failed(fromStatus(e.getStatusCode(), e.getResponseBodyAsString()), e.getResponseBodyAsString());
        }
    }

    @Override
    public VendorResult<IdvOutcome> status(VendorRef ref) {
        try {
            OnfidoDtos.CheckResponse check = client.get()
                    .uri("/checks/{id}", ref.value())
                    .retrieve()
                    .body(OnfidoDtos.CheckResponse.class);

            if (check == null) {
                return failed(new VendorFailure.InvalidRequest("empty-check-response"), null);
            }
            if (!check.isComplete()) {
                // Still running. Staying Pending keeps the deadline and the poller in charge.
                return new VendorResult.Pending<>(ref, raw(check));
            }
            // The check carries the verdict; the report carries the reason that makes it actionable.
            OnfidoDtos.ReportResponse report = firstDocumentReport(check);
            return new VendorResult.Completed<>(map(check, report), raw(List.of(check, report == null ? "" : report)));
        } catch (ResourceAccessException e) {
            return failed(fromTransport(e), describe(e));
        } catch (org.springframework.web.client.RestClientResponseException e) {
            return failed(fromStatus(e.getStatusCode(), e.getResponseBodyAsString()), e.getResponseBodyAsString());
        }
    }

    private String createApplicant(ApplicantSubjects.Subject subject) {
        OnfidoDtos.ApplicantResponse response = client.post()
                .uri("/applicants")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OnfidoDtos.ApplicantRequest(subject.firstName(), subject.lastName(),
                        subject.dateOfBirth().toString(), new OnfidoDtos.Address(subject.country())))
                .retrieve()
                .body(OnfidoDtos.ApplicantResponse.class);
        return response == null ? null : response.id();
    }

    private String uploadDocument(String applicantId, Documents.Content content) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("applicant_id", applicantId);
        form.add("type", "passport");
        form.add("file", new ByteArrayResource(content.bytes()) {
            @Override
            public String getFilename() {
                return content.documentId() + content.fileExtension();
            }
        });

        OnfidoDtos.DocumentResponse response = client.post()
                .uri("/documents")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(OnfidoDtos.DocumentResponse.class);
        return response == null ? null : response.id();
    }

    /**
     * Any check this applicant already has. Each applicant is registered for one {@link VendorCheck}, so any
     * check found here came from an earlier attempt of ours. If two exist, both are for the same document and
     * either is a correct answer; the second was already paid for and cannot be unpaid.
     */
    private Optional<OnfidoDtos.CheckResponse> existingCheck(String applicantId) {
        OnfidoDtos.CheckList list = client.get()
                .uri(uri -> uri.path("/checks").queryParam("applicant_id", applicantId).build())
                .retrieve()
                .body(OnfidoDtos.CheckList.class);
        if (list == null || list.checks() == null) {
            return Optional.empty();
        }
        return list.checks().stream().filter(check -> check.id() != null).findFirst();
    }

    private VendorResult<IdvOutcome> createCheck(String applicantId, String onfidoDocumentId, IdempotencyKey key) {
        OnfidoDtos.CheckResponse check = client.post()
                .uri("/checks")
                // Sent in case Onfido ever deduplicates on it. It does not today, which is what existingCheck
                // is for.
                .header("Idempotency-Key", key.value())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OnfidoDtos.CheckRequest(applicantId, List.of(DOCUMENT_REPORT),
                        List.of(onfidoDocumentId)))
                .retrieve()
                .body(OnfidoDtos.CheckResponse.class);

        if (check == null || check.id() == null) {
            return failed(new VendorFailure.InvalidRequest("empty-check-response"), null);
        }
        return resultOf(check, false);
    }

    /** @param adopted the check came from the applicant's existing checks, not from creating one (FR11) */
    private VendorResult<IdvOutcome> resultOf(OnfidoDtos.CheckResponse check, boolean adopted) {
        if (check.isComplete()) {
            // Allowed by the API and produced by some mocks; handled rather than assumed away.
            return new VendorResult.Completed<>(map(check, firstDocumentReport(check)), raw(check), adopted);
        }
        return new VendorResult.Pending<>(new VendorRef(check.id()), raw(check), adopted);
    }

    private OnfidoDtos.ReportResponse firstDocumentReport(OnfidoDtos.CheckResponse check) {
        if (check.report_ids() == null || check.report_ids().isEmpty()) {
            return null;
        }
        return client.get()
                .uri("/reports/{id}", check.report_ids().getFirst())
                .retrieve()
                .body(OnfidoDtos.ReportResponse.class);
    }

    /**
     * Onfido's result pair to ours. The interesting rows are the two {@code consider}s: a suspected forgery is
     * an <em>answer</em> (FRAUD, requirement satisfied), while a rejected image is a request to the applicant
     * (UNREADABLE, requirement needs evidence).
     */
    private static IdvOutcome map(OnfidoDtos.CheckResponse check, OnfidoDtos.ReportResponse report) {
        String result = lower(check.result());
        String subResult = report == null ? null : lower(report.sub_result());
        return switch (result) {
            case "clear" -> IdvOutcome.VERIFIED;
            case "consider" -> switch (subResult == null ? "" : subResult) {
                case "suspected" -> IdvOutcome.FRAUD;
                case "rejected" -> IdvOutcome.UNREADABLE;
                // "caution" and anything else: an answer with a caveat. Judging it is the ruleset's job.
                default -> IdvOutcome.VERIFIED;
            };
            // "unidentified", or a result we do not recognise: treat as unreadable and ask for another
            // document rather than inventing a verdict.
            default -> IdvOutcome.UNREADABLE;
        };
    }

    /**
     * A read timeout means the vendor was reached and was slow; anything else at this layer means it was not
     * reached at all. Both are retryable, but they are different operational problems and the audit log should
     * not call them the same thing.
     */
    private static VendorFailure fromTransport(ResourceAccessException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof java.net.SocketTimeoutException) {
                return new VendorFailure.Timeout();
            }
        }
        return new VendorFailure.Unavailable(0);
    }

    /** The exception chain as a string, so a failed call leaves something diagnosable on the check. */
    private static String describe(Throwable e) {
        StringBuilder text = new StringBuilder();
        for (Throwable cause = e; cause != null && text.length() < 500; cause = cause.getCause()) {
            text.append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage()).append(" | ");
        }
        return text.toString();
    }

    private static VendorFailure fromStatus(HttpStatusCode status, String body) {
        if (status.value() == 429 || status.is5xxServerError()) {
            return new VendorFailure.Unavailable(status.value());
        }
        if (status.value() == 404) {
            return new VendorFailure.SubjectNotFound();
        }
        return new VendorFailure.InvalidRequest("http-" + status.value());
    }

    private <T> VendorResult<T> failed(VendorFailure failure, String raw) {
        return new VendorResult.Failed<>(failure, raw);
    }

    private String raw(Object value) {
        return json.writeValueAsString(value);
    }


    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
