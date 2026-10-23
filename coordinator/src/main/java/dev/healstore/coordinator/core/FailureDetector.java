package dev.healstore.coordinator.core;

import java.time.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class FailureDetector {
    private final JdbcTemplate db;
    private final Clock clock;
    private final Duration timeout, delay;
    private final Instant started;
    @org.springframework.beans.factory.annotation.Autowired
    public FailureDetector(JdbcTemplate db, @Value("${store.heartbeat-timeout-seconds:10}") long timeout,
        @Value("${store.repair-delay-seconds:20}") long delay) {
        this(db, Clock.systemUTC(), Duration.ofSeconds(timeout), Duration.ofSeconds(delay));
    }
    public FailureDetector(JdbcTemplate db, Clock clock, Duration timeout, Duration delay) {
        if (timeout.isNegative() || timeout.isZero() || delay.isNegative())
            throw new IllegalArgumentException("Invalid failure detector intervals");
        this.db = db;
        this.clock = clock;
        this.timeout = timeout;
        this.delay = delay;
        started = clock.instant();
    }
    public void heartbeat(UUID id) {
        if (db.update("UPDATE node_registrations SET last_seen=? WHERE id=?",
                java.sql.Timestamp.from(clock.instant()), id)
            != 1)
            throw new IllegalArgumentException("Node is not registered");
    }
    private Instant seen(UUID id) {
        return db
            .query("SELECT last_seen FROM node_registrations WHERE id=?",
                (rs, n)
                    -> {
                    var value = rs.getTimestamp(1);
                    return value == null ? Instant.EPOCH : value.toInstant();
                },
                id)
            .stream()
            .findFirst()
            .orElse(Instant.EPOCH);
    }
    public boolean live(UUID id) {
        Instant seen = seen(id);
        return !seen.isBefore(started) && clock.instant().isBefore(seen.plus(timeout));
    }
    public boolean repairable(UUID id) {
        return !clock.instant().isBefore(started.plus(timeout).plus(delay))
            && !clock.instant().isBefore(seen(id).plus(timeout).plus(delay));
    }
}
