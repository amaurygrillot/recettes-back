package ilenreste.unpeu.recettesback.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the gotcha documented in docs/security-error-handling.md: a @Valid failure
 * on a permitAll() endpoint must surface as 400, not get swallowed into 403 by the security filter
 * chain rejecting the servlet container's internal forward to /error.
 * <p>
 * This has to run against the real embedded container ({@code webEnvironment = RANDOM_PORT}, a real
 * HTTP call): a standalone {@code MockMvcBuilders.standaloneSetup(...)} controller test has no
 * security filter chain at all, so it cannot reproduce this class of bug - which is also why the
 * public-endpoint matchers in SecurityFilterConfig (e.g. for PUT /users/reinit-password) need their
 * own real-chain coverage here rather than relying on the standalone controller tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class SecurityErrorHandlingTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void validationFailureOnPublicEndpoint_returnsBadRequest_notForbidden() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("{\"email\":\"\"}", headers);

        ResponseEntity<String> response =
                restTemplate.postForEntity("/auth/reinit-password", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void reinitPasswordEndpoint_isReachableWithoutAuthentication_throughRealFilterChain() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(
                "{\"email\":\"ghost@example.com\",\"token\":\"not-a-real-token\",\"newPassword\":\"SomePassword123\"}",
                headers
        );

        ResponseEntity<String> response = restTemplate.exchange(
                "/users/reinit-password", HttpMethod.PUT, request, String.class
        );

        // No JWT was sent. 401/403 would mean SecurityFilterConfig's permitAll() rule for this route
        // regressed; 400 proves the request reached UserController's business logic instead (an
        // unknown email/token correctly reported as a bad request).
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
