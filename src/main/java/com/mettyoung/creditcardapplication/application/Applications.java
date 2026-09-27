package com.mettyoung.creditcardapplication.application;

import java.util.List;
import java.util.UUID;

/**
 * The application module's API, and the only way in. The aggregate, its value objects, the repository and the
 * endpoints stay inside; a caller outside the module can name this interface, {@link ApplicationResponse},
 * {@link CreateDraftCommand}, {@link UpdateDraftCommand}, {@link CardProduct} and {@link ApplicationStatus}, and nothing else.
 * <p>
 * Every method takes the owner's id and loads by it, so absent and not-yours are one outcome and an id cannot
 * be probed. Refusals are {@code DomainException}s carrying a {@code Category}; the category is the contract,
 * which is why the exception classes themselves are internal.
 */
public interface Applications {

    ApplicationResponse createDraft(String userId, CreateDraftCommand command);

    /** @param command carries the stale-copy precondition, so the form and the version it guards travel as one */
    ApplicationResponse updateDraft(String userId, UUID id, UpdateDraftCommand command);

    ApplicationResponse get(String userId, UUID id);

    /** @param status optional filter; null means every status */
    List<ApplicationResponse> list(String userId, ApplicationStatus status);
}
