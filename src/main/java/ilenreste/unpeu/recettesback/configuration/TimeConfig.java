package ilenreste.unpeu.recettesback.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

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
 * The older {@code Instant.now()} calls in {@code PasswordResetTokenService} and
 * {@code AuthenticationController} are deliberately left alone for now. Both
 * compare an instant to a stored one rather than measuring a window, so they are
 * testable as they stand, and rewriting them is a change with no test to justify
 * it.
 */
@Configuration
public class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
