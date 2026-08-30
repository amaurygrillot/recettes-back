package ilenreste.unpeu.recettesback.configuration;

import ilenreste.unpeu.recettesback.entities.users.RoleEntity;
import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.entities.users.UserRolesEntity;
import ilenreste.unpeu.recettesback.repositories.users.RolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UserRolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
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

import java.util.UUID;

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

    private static final String PASSWORD = "TestPassword123";
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

    private UserEntity user;
    private UserRolesEntity userRoleLink;

    @BeforeEach
    void setUp() {
        RoleEntity userRole = rolesRepository.findByNameEqualsIgnoreCase("USER");
        if (userRole == null) {
            userRole = new RoleEntity();
            userRole.setName("USER");
            userRole = rolesRepository.save(userRole);
        }

        // A unique account per run: this test drives a real HTTP call against the real database, so
        // there is no transaction to roll back and nothing may collide with existing rows.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserEntity newUser = new UserEntity();
        newUser.setUsername("auth-test-" + suffix);
        newUser.setEmail("auth-test-" + suffix + "@example.com");
        newUser.setPassword(passwordEncoder.encode(PASSWORD));
        newUser.setEnabled(true);
        user = usersRepository.save(newUser);

        // The role link is written through UserRolesEntity, not through UserEntity.roles, because
        // that is what UserService.createUser does. The user_roles table is mapped twice - as the
        // @ManyToMany join table AND as an entity with its own id column - so a @ManyToMany insert
        // leaves that NOT NULL id null and fails. Reads still go through the @ManyToMany side.
        UserRolesEntity link = new UserRolesEntity();
        link.setUser(user);
        link.setRole(userRole);
        userRolesRepository.save(link);
        userRoleLink = link;
    }

    @AfterEach
    void tearDown() {
        // delete(entity), not deleteById: UserRolesRepository declares UUID as its id type
        // while UserRolesEntity.id is a String, so deleteById does not compile.
        userRolesRepository.delete(userRoleLink);
        usersRepository.deleteById(user.getId());
    }

    private HttpEntity<String> body(String json, String authorization) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (authorization != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        return new HttpEntity<>(json, headers);
    }

    private String loginBody() {
        return "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(user.getUsername(), PASSWORD);
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
                .formatted(user.getUsername());

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
    }

    /**
     * The public-read rule, asserted before the controllers exist: 404 means the request was
     * permitted and simply found no handler. A 401 here would mean the permitAll() rule is missing
     * or ordered below something broader.
     */
    @Test
    void publicReadRoutes_arePermitted_withoutAnyCredential() {
        for (String path : new String[]{"/recipes", "/media/some-id", "/tags", "/units", "/categories"}) {
            ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);

            assertThat(response.getStatusCode())
                    .as("anonymous GET %s must not be rejected by the security chain", path)
                    .isEqualTo(HttpStatus.NOT_FOUND);
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
