package ilenreste.unpeu.recettesback.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * The application's source of "now".
 * <p>
 * A bean rather than {@code Instant.now()} at the call site, because anything
 * that reasons about a <em>window</em> of time is otherwise untestable without
 * sleeping. {@code UploadRateLimiter} allows so many uploads per ten minutes; a
 * test that cannot move the clock can only assert the limit by making the window
 * a second long and waiting it out, which is both slow and flaky.
 * <p>
 * {@link Clock#systemUTC()} and not {@code systemDefaultZone()}: nothing here
 * formats a local time, and a UTC instant is the same instant whatever the VPS
 * has its timezone set to.
 * <p>
 * <strong>It ticks in microseconds.</strong> PostgreSQL keeps {@code timestamptz} to the microsecond and the JDK reads
 * the system clock much finer (100 ns on Windows, a nanosecond elsewhere). An instant the application kept in memory and
 * answered after a write therefore had a finer fraction than the one the next read gave back, rounded by the
 * database, and a client comparing the two saw a change after every refetch. Truncating <em>here</em> makes every
 * instant of the API representable in the column that stores it, for every entity, and nothing is truncated in a response
 * or a mapper. JPA auditing reads this clock too ({@link JpaAuditingConfig}), which is what covers {@code createdAt} and
 * {@code updatedAt} everywhere.
 * <p>
 * The older {@code Instant.now()} calls in {@code PasswordResetTokenService} and
 * {@code AuthenticationController} are deliberately left alone for now: neither is answered by the API (one is a token's
 * expiry, the other a JWT claim in whole seconds). Both compare an instant to a stored one rather than measuring a window,
 * so they are testable as they stand, and rewriting them is a change with no test to justify it.
 */
@Configuration
public class TimeConfig {

    private static final Duration MICROSECOND = Duration.ofNanos(1_000);

    @Bean
    Clock clock() {
        return truncatedToMicroseconds(Clock.systemUTC());
    }

    /** Rounds down, never up: an instant is never later than the moment it was read. */
    static Clock truncatedToMicroseconds(Clock clock) {
        return Clock.tick(clock, MICROSECOND);
    }
}
