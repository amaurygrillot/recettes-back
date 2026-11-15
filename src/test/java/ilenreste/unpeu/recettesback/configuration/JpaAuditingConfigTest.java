package ilenreste.unpeu.recettesback.configuration;

import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
import ilenreste.unpeu.recettesback.services.users.CurrentUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The guard on <em>who</em> the auditor is lives in {@link CurrentUserService} and is tested there.
 * What is left here is the wiring: that the auditor is resolved as a lazy reference, and that an
 * absent user produces an empty auditor rather than a null one.
 */
class JpaAuditingConfigTest {

    private final UsersRepository usersRepository = mock(UsersRepository.class);

    @SuppressWarnings("unchecked")
    private AuditorAware<UserEntity> auditorAware() {
        ObjectProvider<UsersRepository> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(usersRepository);
        return new JpaAuditingConfig().auditorAware(provider, new CurrentUserService());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvesTheAuditorAsALazyReference_ratherThanLoadingTheUser() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("userId", "user-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        UserEntity reference = new UserEntity();
        // getReferenceById, not findById: the foreign key is genuine without a SELECT on every save.
        when(usersRepository.getReferenceById("user-1")).thenReturn(reference);

        assertThat(auditorAware().getCurrentAuditor()).contains(reference);
    }

    /**
     * {@code createdAt} and {@code updatedAt} are stamped from the application's clock, not from the system's: that is the
     * one place instants are truncated to what the column keeps, so a write's answer and the next read agree.
     */
    @Test
    void stampsTheDatesFromTheApplicationsClock() {
        Instant instant = Instant.parse("2026-11-15T10:00:00.123456Z");
        Clock clock = Clock.fixed(instant, ZoneOffset.UTC);

        Optional<TemporalAccessor> now = new JpaAuditingConfig().auditingDateTimeProvider(clock).getNow();

        assertThat(now).contains(instant);
    }

    @Test
    void returnsEmpty_whenThereIsNoAuthenticatedUser() {
        assertThat(auditorAware().getCurrentAuditor()).isEmpty();
        verifyNoInteractions(usersRepository);
    }

    @Test
    void returnsEmpty_forAnAnonymousRequest() {
        // POST /users/create is permitAll() and writes, so this path is reachable - and if it threw,
        // every anonymous write against an audited entity would be a 500.
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThat(auditorAware().getCurrentAuditor()).isEmpty();
        verifyNoInteractions(usersRepository);
    }
}
