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

import java.time.Instant;
import java.util.List;

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
