package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.audit.Audits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * FR11: the open timeline streams, fed by polling the audit log. The events are written by workers and the
 * orchestrator in other transactions, possibly on another instance, so there is no in-memory event to forward; the
 * log's {@code seq} is the column and this is the poller.
 * <p>
 * One ticker for every open stream, not a thread each: an idle browser costs a query a second. Its own executor,
 * not {@code @Scheduled}, because the view must work whether or not the workers' scheduler is on.
 */
@Component
@ConditionalOnProperty(name = "app.ui.timeline.enabled", havingValue = "true")
class TimelineStreams implements SmartLifecycle {

    /** A stream ends after this and the browser reconnects with Last-Event-ID, losing nothing. */
    static final long TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(10);

    private static final Logger log = LoggerFactory.getLogger(TimelineStreams.class);

    private final Audits audits;
    private final ApplicationRepository applications;
    private final Set<Stream> streams = ConcurrentHashMap.newKeySet();
    private volatile boolean running = true;
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "timeline-ticker");
        thread.setDaemon(true);
        return thread;
    });

    TimelineStreams(Audits audits, ApplicationRepository applications) {
        this.audits = audits;
        this.applications = applications;
        ticker.scheduleWithFixedDelay(this::tick, 0, 1, TimeUnit.SECONDS);
    }

    /** @param afterSeq the last event the browser has, from Last-Event-ID; 0 for all of them */
    SseEmitter open(UUID applicationId, long afterSeq) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        Stream stream = new Stream(applicationId, emitter, afterSeq);
        emitter.onCompletion(() -> streams.remove(stream));
        emitter.onTimeout(() -> streams.remove(stream));
        emitter.onError(error -> streams.remove(stream));
        streams.add(stream);
        return emitter;
    }

    private void tick() {
        for (Stream stream : streams) {
            try {
                poll(stream);
            } catch (IOException | IllegalStateException e) {
                // The browser went away, or the emitter is already done. Either way this stream is over.
                streams.remove(stream);
            } catch (RuntimeException e) {
                // Never let one stream's failure stop the ticker for the others.
                log.warn("Timeline stream for {} failed; closing it", stream.applicationId, e);
                streams.remove(stream);
                stream.emitter.completeWithError(e);
            }
        }
    }

    private void poll(Stream stream) throws IOException {
        List<Audits.RecordedEvent> events = audits.since(stream.applicationId, stream.lastSeq);
        for (Audits.RecordedEvent event : events) {
            stream.emitter.send(SseEmitter.event()
                    .id(Long.toString(event.seq()))
                    .name(event.type().name())
                    .data(asData(event), MediaType.APPLICATION_JSON));
            stream.lastSeq = event.seq();
        }
        // FR11.4: once terminal and drained, nothing more will come - the decision is the last event.
        if (events.isEmpty() && applications.findById(stream.applicationId).map(Application::isTerminal).orElse(true)) {
            streams.remove(stream);
            stream.emitter.complete();
        }
    }

    private static Map<String, Object> asData(Audits.RecordedEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("seq", event.seq());
        data.put("type", event.type());
        data.put("actor", event.actor());
        data.put("at", event.at());
        data.put("payload", event.payload());
        return data;
    }

    /**
     * Ends every open stream before the web server's graceful shutdown, which runs at a lower phase and would
     * otherwise wait its full timeout for these never-ending requests. A browser simply reconnects to the next
     * instance with Last-Event-ID.
     */
    @Override
    public void stop() {
        running = false;
        ticker.shutdownNow();
        for (Stream stream : streams) {
            streams.remove(stream);
            try {
                stream.emitter.complete();
            } catch (RuntimeException e) {
                // Already gone with its connection; nothing left to end.
            }
        }
    }

    @Override
    public void start() {
        // The ticker starts in the constructor; a stopped context is not restarted.
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static final class Stream {
        private final UUID applicationId;
        private final SseEmitter emitter;
        private volatile long lastSeq;

        private Stream(UUID applicationId, SseEmitter emitter, long lastSeq) {
            this.applicationId = applicationId;
            this.emitter = emitter;
            this.lastSeq = lastSeq;
        }
    }
}
