package br.com.pedrodalben.easyvip.redis;

import java.util.concurrent.atomic.LongAdder;

/** Process-local counters safe to export to a metrics system later. */
public final class RedisMetrics {
    private final LongAdder published = new LongAdder();
    private final LongAdder publishFailures = new LongAdder();
    private final LongAdder received = new LongAdder();
    private final LongAdder invalidEvents = new LongAdder();
    private final LongAdder ignoredEvents = new LongAdder();
    private final LongAdder commandFailures = new LongAdder();

    public void published() { published.increment(); }
    public void publishFailed() { publishFailures.increment(); }
    public void received() { received.increment(); }
    public void invalidEvent() { invalidEvents.increment(); }
    public void ignoredEvent() { ignoredEvents.increment(); }
    public void commandFailed() { commandFailures.increment(); }

    public Snapshot snapshot() {
        return new Snapshot(published.sum(), publishFailures.sum(), received.sum(), invalidEvents.sum(), ignoredEvents.sum(), commandFailures.sum());
    }

    public record Snapshot(long published, long publishFailures, long received, long invalidEvents, long ignoredEvents, long commandFailures) { }
}
