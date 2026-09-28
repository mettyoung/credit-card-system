package com.mettyoung.creditcardapplication.document.internal;

import com.mettyoung.creditcardapplication.document.DocumentStatus;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Package-private: a scheduled bean is nobody's collaborator. Spring drives it by the annotation, and the
 * only other caller is its own spec, which sits in this package.
 * <p>
 * Sweeps upload URLs that were issued and never used. The timer is a column
 * ({@code document.created_at}) plus this poller, so nothing is held in memory and a restart loses nothing.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class UploadCleanup {

    private static final Duration STALE_AFTER = Duration.ofHours(1);

    private final DocumentRepository repository;
    private final ObjectStore objectStore;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${app.workers.upload-cleanup-interval:15m}")
    @Transactional
    public int sweep() {
        Instant cutoff = Instant.now(clock).minus(STALE_AFTER);
        List<Document> stale = repository.findByStatusAndCreatedAtBefore(DocumentStatus.PENDING_UPLOAD, cutoff);
        for (Document document : stale) {
            // Delete the object first: a row marked EXPIRED with bytes still in the bucket would be a leak
            // nothing looks at again, whereas a deleted object with the row still PENDING_UPLOAD is swept
            // again on the next pass.
            objectStore.delete(document.getObjectKey());
            document.markExpired();
            repository.save(document);
        }
        return stale.size();
    }
}
