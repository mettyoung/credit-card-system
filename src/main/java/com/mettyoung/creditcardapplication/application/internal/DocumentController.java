package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.document.DocumentVerified;
import com.mettyoung.creditcardapplication.document.Uploads;
import com.mettyoung.creditcardapplication.document.RequestUploadCommand;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

/**
 * Upload endpoints. Bytes never pass through here: the client gets a pre-signed URL and PUTs straight to the
 * object store, then confirms, and the server verifies the object.
 * <p>
 * In the {@code application} module, not {@code document}, because the resource is application-scoped
 * ({@code /v1/applications/{id}/documents}) and the guard on it is the application's. Keeping it here is also
 * what stops {@code document} depending on {@code application}, which would be a cycle.
 * <p>
 * It asks {@code ApplicationService} directly rather than through {@link Applications}: both are inside this
 * module, and the ownership guard is not something the facade promises anyone else.
 */
@RestController
@RequestMapping(DocumentController.BASE_PATH)
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class DocumentController {

    static final String BASE_PATH = "/v1/applications/{applicationId}/documents";
    static final String USER_ID_HEADER = "X-User-Id";

    private final Uploads uploads;
    private final ApplicationService applications;

    @PostMapping
    ResponseEntity<Uploads.Upload> requestUpload(@RequestHeader(USER_ID_HEADER) UserId userId,
                                                    @PathVariable UUID applicationId,
                                                    @RequestBody RequestUploadCommand request) {
        // Uploading is only allowed while the application can still accept evidence. Asking the application
        // service keeps that rule in one place instead of duplicating the status check here.
        applications.requireUploadable(userId.value(), applicationId);

        Uploads.Upload upload = uploads.requestUpload(applicationId, userId.value(), request);
        return ResponseEntity.created(URI.create(path(applicationId) + "/" + upload.documentId())).body(upload);
    }

    @PostMapping("/{documentId}/complete")
    DocumentVerified completeUpload(@RequestHeader(USER_ID_HEADER) UserId userId,
                                    @PathVariable UUID applicationId,
                                    @PathVariable UUID documentId) {
        return uploads.completeUpload(applicationId, documentId, userId.value());
    }

    @GetMapping("/{documentId}")
    DocumentVerified get(@RequestHeader(USER_ID_HEADER) UserId userId,
                         @PathVariable UUID applicationId,
                         @PathVariable UUID documentId) {
        return uploads.get(applicationId, documentId, userId.value());
    }

    private static String path(UUID applicationId) {
        return "/v1/applications/" + applicationId + "/documents";
    }
}
