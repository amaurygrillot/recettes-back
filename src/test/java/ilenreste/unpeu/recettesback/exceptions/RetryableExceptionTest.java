package ilenreste.unpeu.recettesback.exceptions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one place the wait of a 429 or a 503 becomes whole seconds, for the {@code Retry-After} header and for the
 * sentence in {@code detail} alike.
 */
class RetryableExceptionTest {

    /**
     * Rounded UP: a client that obeys the header must never come back early and be refused a second time. The
     * floor of one is for the sub-second waits, which would otherwise read as "immediately".
     */
    @ParameterizedTest(name = "{0} ms -> {1} s")
    @CsvSource({
            "0, 1",
            "1, 1",
            "120, 1",
            "999, 1",
            "1000, 1",
            "1001, 2",
            "59400, 60",
            "60000, 60",
            "599600, 600",
            "600000, 600",
            "600001, 601",
            "-500, 1"
    })
    void wholeSeconds_roundsUp_andNeverGoesBelowOne(long milliseconds, long expectedSeconds) {
        assertThat(RetryableException.wholeSeconds(Duration.ofMillis(milliseconds))).isEqualTo(expectedSeconds);
    }

    @Test
    void aNanosecondOverAWholeSecond_isTheNextSecond() {
        assertThat(RetryableException.wholeSeconds(Duration.ofSeconds(30).plusNanos(1))).isEqualTo(31);
    }

    @Test
    void bothRetryableExceptionsExposeTheSameRounding() {
        Duration wait = Duration.ofMillis(2_500);

        assertThat(new RateLimitExceededException("limited", wait).retryAfterSeconds()).isEqualTo(3);
        assertThat(new ServiceOverloadedException("busy", wait).retryAfterSeconds()).isEqualTo(3);
        assertThat(new RateLimitExceededException("limited", wait).retryAfter()).isEqualTo(wait);
    }
}
