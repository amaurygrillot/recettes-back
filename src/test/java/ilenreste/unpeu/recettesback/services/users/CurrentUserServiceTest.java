package ilenreste.unpeu.recettesback.services.users;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CurrentUserServiceTest {

    private final CurrentUserService currentUserService = new CurrentUserService();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String userId, String... roles) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("userId", userId)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList(roles)));
    }

    @Test
    void readsTheUserIdFromTheTokenClaim() {
        authenticate("user-1", "ROLE_USER");

        assertThat(currentUserService.currentUserId()).contains("user-1");
    }

    @Test
    void reportsNoUser_whenThereIsNoAuthenticationAtAll() {
        assertThat(currentUserService.currentUserId()).isEmpty();
        assertThat(currentUserService.isAdmin()).isFalse();
    }

    /**
     * The regression this guard exists for. Anonymous requests continue down the filter chain, and
     * Spring Security's AnonymousAuthenticationFilter puts an AnonymousAuthenticationToken in the
     * context whose principal is the String "anonymousUser" - so "no authentication" is not
     * authentication == null, and a null check followed by a cast throws ClassCastException. Every
     * GET here is public, so that path is reached constantly.
     */
    @Test
    void reportsNoUser_forAnAnonymousToken_ratherThanThrowing() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThatCode(currentUserService::currentUserId).doesNotThrowAnyException();
        assertThat(currentUserService.currentUserId()).isEmpty();
        assertThat(currentUserService.isAdmin()).isFalse();
    }

    /**
     * Checking isAuthenticated() would not have caught the case above: AnonymousAuthenticationToken
     * reports true. Asserting that here means the guard cannot be "simplified" into that weaker
     * check without a red test.
     */
    @Test
    void anonymousTokenReportsItselfAuthenticated_whichIsWhyTheGuardIsATypeCheck() {
        AnonymousAuthenticationToken anonymous = new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        assertThat(anonymous.isAuthenticated()).isTrue();
        assertThat(anonymous.getPrincipal()).isInstanceOf(String.class);
    }

    @Test
    void recognisesAnAdminFromTheTokenAuthorities() {
        authenticate("user-1", "ROLE_USER", "ROLE_ADMIN");

        assertThat(currentUserService.isAdmin()).isTrue();
    }

    @Test
    void doesNotTreatAPlainUserAsAnAdmin() {
        authenticate("user-1", "ROLE_USER");

        assertThat(currentUserService.isAdmin()).isFalse();
    }

    @Test
    void doesNotMistakeASimilarlyNamedAuthorityForAdmin() {
        // "ROLE_ADMINISTRATOR" must not match: a prefix or contains check here would silently grant
        // admin rights to any role whose name starts the same way.
        authenticate("user-1", "ROLE_ADMINISTRATOR", "ROLE_SUBADMIN");

        assertThat(currentUserService.isAdmin()).isFalse();
    }

    @Test
    void toleratesATokenWithNoAuthoritiesAtAll() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("userId", "user-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));

        assertThat(currentUserService.currentUserId()).contains("user-1");
        assertThat(currentUserService.isAdmin()).isFalse();
    }
}
