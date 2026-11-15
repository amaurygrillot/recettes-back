package ilenreste.unpeu.recettesback.configuration;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application's "now" ticks in microseconds, which is what PostgreSQL keeps, so that an instant the API answers
 * after a write is the one the next read gives back. See docs/recipe-list-cover.md ("The precision of createdAt and
 * updatedAt").
 */
class TimeConfigTest {

    private final Clock clock = new TimeConfig().clock();

    @Test
    void noInstantIsFinerThanAMicrosecond() {
        // Enough samples that a clock with nanosecond (or 100 ns) resolution would show a remainder.
        for (int sample = 0; sample < 5_000; sample++) {
            assertThat(clock.instant().getNano() % 1_000).as("sub-microsecond part of %s", clock.instant()).isZero();
        }
    }

    @Test
    void itTruncatesAndNeverRoundsUp() {
        Clock base = Clock.fixed(Instant.parse("2026-11-15T10:00:00.123456999Z"), ZoneOffset.UTC);

        assertThat(TimeConfig.truncatedToMicroseconds(base).instant())
                .isEqualTo(Instant.parse("2026-11-15T10:00:00.123456Z"));
    }

    @Test
    void itStillTellsTheTime() {
        Instant before = Instant.now();
        Instant now = clock.instant();
        Instant after = Instant.now();

        assertThat(now).isBetween(before.minusNanos(1_000), after);
    }

    @Test
    void itIsInUtc() {
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
