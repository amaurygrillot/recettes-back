package ilenreste.unpeu.recettesback.contract;

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
 * The mappings generated from {@code openapi.yaml} must not narrow what a client may
 * {@code Accept}; see {@code GeneratedMappingsConfig}.
 * <p>
 * Most HTTP wrappers send {@code Accept: application/json} on every call, including the ones that
 * answer with no body, and the hand-written controllers served all of them. A generated
 * {@code produces} condition listing only {@code application/problem+json} (all an operation with no
 * success body has) turns that into a 406 raised by the handler mapping, before the controller runs.
 * The generated {@code consumes} condition does the same to a request with no body and no
 * {@code Content-Type}, turning a 400 into a 415. These are the shapes that went wrong, against the
 * real server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class GeneratedMappingsNegotiationTest {

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

    private TestAccount admin;
    private String adminToken;

    @BeforeEach
    void setUp() {
        admin = TestAccount.create(usersRepository, rolesRepository, userRolesRepository, passwordEncoder,
                "USER", "ADMIN");
        adminToken = admin.bearerToken(restTemplate);
    }

    @AfterEach
    void tearDown() {
        admin.close();
    }

    @Test
    void anOperationWithNoSuccessBodyStillAnswersAClientThatOnlyAcceptsJson() {
        // 404 and not 406: the request got past the mapping and reached TagService, which found nothing.
        ResponseEntity<String> response = restTemplate.exchange("/tags/no-such-tag", HttpMethod.DELETE,
                new HttpEntity<>(null, jsonOnly(adminToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void passwordResetRequestAnswersAcceptedToAClientThatOnlyAcceptsJson() {
        // The anti-enumeration endpoint answers 202 for any address; a 406 here would also tell a caller
        // nothing about the address, but it would break the flow for every JSON-only client.
        ResponseEntity<String> response = restTemplate.exchange("/auth/reinit-password", HttpMethod.POST,
                new HttpEntity<>("{\"email\":\"nobody-at-all@example.com\"}", jsonBodyAcceptingOnlyJson()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void loginReturnsItsPlainTextTokenToAClientThatOnlyAcceptsJson() {
        String credentials = "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(admin.username(),
                TestAccount.PASSWORD);

        ResponseEntity<String> response = restTemplate.exchange("/auth/login", HttpMethod.POST,
                new HttpEntity<>(credentials, jsonBodyAcceptingOnlyJson()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotBlank().doesNotStartWith("{");
    }

    @Test
    void loginStillAnswersTextPlainWhenTheClientAcceptsAnything() {
        String credentials = "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(admin.username(),
                TestAccount.PASSWORD);

        ResponseEntity<String> response = restTemplate.exchange("/auth/login", HttpMethod.POST,
                body(credentials, null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_PLAIN)).isTrue();
    }

    @Test
    void aRequestWithNoBodyAndNoContentTypeIsStillAMissingBodyAndNotAnUnsupportedMediaType() {
        // A bare `curl -X POST` sends neither. It has always been a 400 from the missing @RequestBody; a
        // generated consumes condition would answer 415 first.
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, adminToken);

        ResponseEntity<String> response = restTemplate.exchange("/tags", HttpMethod.POST,
                new HttpEntity<>(null, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static HttpHeaders jsonOnly(String authorization) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, authorization);
        return headers;
    }

    private static HttpHeaders jsonBodyAcceptingOnlyJson() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        return headers;
    }
}
