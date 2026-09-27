package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.application.ApplicationResponse;
import com.mettyoung.creditcardapplication.application.Applications;
import com.mettyoung.creditcardapplication.application.ApplicationStatus;
import com.mettyoung.creditcardapplication.application.CreateDraftCommand;
import com.mettyoung.creditcardapplication.application.UpdateDraftCommand;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping(ApplicationController.BASE_PATH)
@RequiredArgsConstructor
class ApplicationController {

    static final String BASE_PATH = "/v1/applications";
    static final String USER_ID_HEADER = "X-User-Id";

    private final Applications applications;

    @PostMapping
    ResponseEntity<ApplicationResponse> createDraft(@RequestHeader(USER_ID_HEADER) UserId userId,
                                                    @Valid @RequestBody CreateDraftCommand command) {
        ApplicationResponse created = applications.createDraft(userId.value(), command);
        return ResponseEntity.created(URI.create(BASE_PATH + "/" + created.id())).body(created);
    }

    @PatchMapping("/{id}")
    ApplicationResponse updateDraft(@RequestHeader(USER_ID_HEADER) UserId userId,
                                           @PathVariable UUID id,
                                           @Valid @RequestBody UpdateDraftCommand command) {
        return applications.updateDraft(userId.value(), id, command);
    }

    @GetMapping("/{id}")
    ApplicationResponse get(@RequestHeader(USER_ID_HEADER) UserId userId, @PathVariable UUID id) {
        return applications.get(userId.value(), id);
    }

    @GetMapping
    ApplicationResponse.Page list(@RequestHeader(USER_ID_HEADER) UserId userId,
                                  @RequestParam(required = false) ApplicationStatus status) {
        return new ApplicationResponse.Page(applications.list(userId.value(), status));
    }

}
