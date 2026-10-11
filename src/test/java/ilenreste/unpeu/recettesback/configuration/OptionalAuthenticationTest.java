package ilenreste.unpeu.recettesback.configuration;

import ilenreste.unpeu.recettesback.exceptions.handlers.ProblemDetailErrorResponder;
import ilenreste.unpeu.recettesback.repositories.users.RolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UserRolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
import ilenreste.unpeu.recettesback.support.TestAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;


import static ilenreste.unpeu.recettesback.support.HttpTestSupport.body;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The authorization matrix from docs/optional-authentication.md, against the real filter chain.
 * <p>
 * None of this is reachable from a {@code standaloneSetup} MockMvc test, which has no security
 * filter chain at all. Several rows here guard regressions rather than features: they pass today
 * only because of one specific line, and would silently start failing the moment that line is
 * "simplified" away.
 * <p>
 * Rows covering {@code /recipes} land with the recipes endpoints; the public-read rule is already
 * asserted below through a route that is permitted but has no controller yet.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class OptionalAuthenticationTest {

    /** Not a JWT at all, standing in for the expired tokens a restart leaves in every client. */
    private static final String STALE_TOKEN = "Bearer eyJhbGciOiJub25lIn0.e30.not-a-real-signature";

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private UsersRepository usersRepository;
    @Autowired
    private RolesRepository rolesRepository;
    @Autowired
    private UserRolesRepository userRolesRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * Through {@link TestAccount} rather than hand-rolled here.
     * <p>
     * This suite used to create the account, find-or-create the USER role and
     * unpick the role link itself — about forty lines that {@code TestAccount},
     * added in the same change, already does, down to the same unique-suffix
     * trick and the same comment explaining why the link is written through
     * {@code UserRolesEntity}. Keeping the copy meant that the day the
     * {@code user_roles} double-mapping is fixed, or {@code UserEntity} gains a
     * NOT NULL column, {@code TestAccount} would be updated and this suite would
     * keep inserting rows the old way — failing on a column nobody touched, in
     * the one place that guards the security filter chain.
     */
    private TestAccount account;

    @BeforeEach
    void setUp() {
        account = TestAccount.create(usersRepository, rolesRepository, userRolesRepository,
                passwordEncoder, "USER");
    }

    @AfterEach
    void tearDown() {
        account.close();
    }

    private String loginBody() {
        return "{\"username\":\"%s\",\"password\":\"%s\"}"
                .formatted(account.username(), TestAccount.PASSWORD);
    }

    /**
     * The lockout regression, and the reason CREDENTIAL_ENDPOINTS exists.
     * <p>
     * RsaKeyConfig mints a fresh key pair at every startup, so every restart invalidates every token
     * in circulation. An SPA whose interceptor attaches its stored token to all requests would be
     * 401'd on login itself and could never obtain a fresh one - the only escape being to clear
     * browser storage by hand. Asserting 200 rather than "not 401" is what makes this meaningful:
     * with the exception removed, this returns 401 from the filter, which is indistinguishable by
     * status alone from a genuine bad-credentials 401.
     */
    @Test
    void login_succeeds_evenWhenTheClientStillAttachesADeadToken() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/auth/login", body(loginBody(), STALE_TOKEN), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotBlank();
    }

    /**
     * Guards the rethrow in ApiExceptionHandler. AuthenticationManager's exception is raised inside
     * the DispatcherServlet, so a global {@code @ExceptionHandler(Exception.class)} that answered it
     * would turn every wrong password into a 500 - the advice has to hand it back to
     * ExceptionTranslationFilter instead.
     */
    @Test
    void login_returns401_notServerError_whenTheCredentialsAreWrong() {
        String wrongPassword = "{\"username\":\"%s\",\"password\":\"definitely-not-it\"}"
                .formatted(account.username());

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/auth/login", body(wrongPassword, null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * A dead session is precisely why someone is on the password-reset screen, so this route must
     * never 401 on a broken token. 400 proves the request reached the controller's business logic.
     */
    @Test
    void reinitPassword_reachesItsBusinessLogic_withADeadTokenStillAttached() {
        ResponseEntity<String> response = restTemplate.exchange("/users/reinit-password", HttpMethod.PUT,
                body("""
                        {"email":"ghost@example.com","token":"not-a-real-token","newPassword":"SomePassword123"}
                        """, STALE_TOKEN),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * The other regression: the filter no longer rejects anonymous callers itself, so 401 now comes
     * only from the explicit AuthenticationEntryPoint. Drop that line and Spring Security falls back
     * to Http403ForbiddenEntryPoint and this silently becomes a 403.
     */
    @Test
    void protectedRoute_returns401_notForbidden_whenNoCredentialIsOffered() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/users/update", HttpMethod.PUT, body("{\"firstname\":\"Janet\"}", null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).contains(ProblemDetailErrorResponder.UNAUTHORIZED_DETAIL);
    }

    /**
     * A non-bearer Authorization header offers no bearer credential at all, so it is treated exactly
     * like a missing header - the chain decides, and here the chain requires authentication. Paired
     * with the test below, which is the case that must NOT be treated the same way.
     */
    @Test
    void nonBearerAuthorizationHeader_isTreatedAsNoCredential() {
        ResponseEntity<String> response = restTemplate.exchange("/users/update", HttpMethod.PUT,
                body("{\"firstname\":\"Janet\"}", "Basic dXNlcjpwYXNz"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * A present-but-broken bearer token is a caller who thinks they are authenticated and is not.
     * Downgrading them to anonymous would turn an expired session into a confusing 403 on the next
     * write, so the filter rejects it outright - everywhere except the credential routes above.
     */
    @Test
    void brokenBearerToken_isRejected_onARouteThatDoesNotHandOutCredentials() {
        ResponseEntity<String> response = restTemplate.exchange("/users/update", HttpMethod.PUT,
                body("{\"firstname\":\"Janet\"}", STALE_TOKEN), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // And with a body. The filter used to write the status by hand, so this - the single most
        // common auth failure, and the one an SPA must recognise to trigger a silent re-login - was
        // the one error in the API with nothing to parse, while every other 401 and 403 came back
        // as problem+json from ProblemDetailErrorResponder. It now goes through that responder too.
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).contains(ProblemDetailErrorResponder.UNAUTHORIZED_DETAIL);
    }

    /**
     * The public-read rule. What matters is that the security chain does not reject these, not what
     * the handler behind them says: 200 (a controller exists), 404 (none yet, or an unknown id) and
     * 400 all prove the request got through. A 401 or 403 would mean the permitAll() rule is
     * missing, or ordered below something broader that swallowed it.
     */
    @Test
    void publicReadRoutes_arePermitted_withoutAnyCredential() {
        for (String path : new String[]{"/recipes", "/media/some-id", "/tags", "/units",
                "/categories", "/ingredients"}) {
            ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);

            assertThat(response.getStatusCode())
                    .as("anonymous GET %s must not be rejected by the security chain", path)
                    .isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        }
    }

    /**
     * Role rules still apply: a plain USER reaching an admin-only write gets 403, in the same
     * problem+json shape as every other error rather than an empty body.
     */
    @Test
    void adminOnlyWrite_returns403_forAPlainUser() {
        ResponseEntity<String> loginResponse = restTemplate.postForEntity(
                "/auth/login", body(loginBody(), null), String.class);
        String token = "Bearer " + loginResponse.getBody();

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/tags", body("{\"name\":\"vegetarien\"}", token), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains(ProblemDetailErrorResponder.FORBIDDEN_DETAIL);
    }
}
