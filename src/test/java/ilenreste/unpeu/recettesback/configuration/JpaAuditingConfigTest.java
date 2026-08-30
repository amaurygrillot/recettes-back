package ilenreste.unpeu.recettesback.configuration;

import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JpaAuditingConfigTest {

    private final UsersRepository usersRepository = mock(UsersRepository.class);

    @SuppressWarnings("unchecked")
    private AuditorAware<UserEntity> auditorAware() {
        ObjectProvider<UsersRepository> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(usersRepository);
        return new JpaAuditingConfig().auditorAware(provider);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsTheUserReference_whenTheRequestCarriesAJwt() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("userId", "user-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        UserEntity reference = new UserEntity();
        when(usersRepository.getReferenceById("user-1")).thenReturn(reference);

        assertThat(auditorAware().getCurrentAuditor()).contains(reference);
    }

    @Test
    void returnsEmpty_whenThereIsNoAuthenticationAtAll() {
        assertThat(auditorAware().getCurrentAuditor()).isEmpty();
        verifyNoInteractions(usersRepository);
    }

    /**
     * The regression this guard exists for. Once public reads land, an anonymous request continues
     * down the filter chain and Spring Security's AnonymousAuthenticationFilter puts an
     * AnonymousAuthenticationToken in the context whose principal is the String "anonymousUser" -
     * so "no authentication" is not authentication == null. A null check followed by a cast takes
     * the non-empty branch here and throws ClassCastException, and POST /users/create is permitAll()
     * and writes, so that path is reachable.
     */
    @Test
    void returnsEmpty_forAnAnonymousToken_ratherThanThrowingClassCastException() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        AuditorAware<UserEntity> auditorAware = auditorAware();

        assertThatCode(auditorAware::getCurrentAuditor).doesNotThrowAnyException();
        assertThat(auditorAware.getCurrentAuditor()).isEmpty();
        verifyNoInteractions(usersRepository);
    }

    /**
     * isAuthenticated() would not have caught the case above: AnonymousAuthenticationToken reports
     * true. This asserts the distinguishing property directly, so the guard cannot be "simplified"
     * into that weaker check without a red test.
     */
    @Test
    void anonymousTokenReportsItselfAuthenticated_whichIsWhyTheGuardIsATypeCheck() {
        AnonymousAuthenticationToken anonymous = new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        assertThat(anonymous.isAuthenticated()).isTrue();
        assertThat(anonymous.getPrincipal()).isInstanceOf(String.class);
    }
}
