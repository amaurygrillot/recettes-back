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
 * security filter chain at all, so it cannot reproduce this class of bug.
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
}
