package ilenreste.unpeu.recettesback.filters;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link JwtAuthenticationFilter} through the real security filter chain: a standalone
 * {@code MockMvcBuilders.standaloneSetup(...)} controller test has no filter chain at all, so this
 * class of behaviour (which endpoints require a bearer token, and what happens when one is missing,
 * malformed, or invalid) can only be verified against the real embedded container. Same rationale as
 * {@code SecurityErrorHandlingTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class JwtAuthenticationFilterTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtEncoder jwtEncoder;

    private String mintToken(String userId, String... roles) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("self")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .subject("jane")
                .claim("userId", userId)
                .claim("roles", List.of(roles))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    private ResponseEntity<String> callProtectedEndpoint(HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("""
                {"firstname":"Janet"}
                """, headers);
        return restTemplate.exchange("/users/update", HttpMethod.PUT, request, String.class);
    }

    @Test
    void protectedEndpoint_returns401_whenAuthorizationHeaderIsMissing() {
        ResponseEntity<String> response = callProtectedEndpoint(new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedEndpoint_returns401_whenAuthorizationHeaderIsNotBearer() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz");

        ResponseEntity<String> response = callProtectedEndpoint(headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedEndpoint_returns401_whenTokenIsInvalid() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-jwt");

        ResponseEntity<String> response = callProtectedEndpoint(headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedEndpoint_isReachable_whenTokenIsValidAndHasRequiredRole() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + mintToken("no-such-user-id", "USER"));

        ResponseEntity<String> response = callProtectedEndpoint(headers);

        // A JWT that fails validation would short-circuit at 401 (no role) or 403 (wrong role); getting
        // as far as 404 proves the filter authenticated the token and let the request reach
        // UserController/UserService, which then rejected the unknown userId claim on its own merits.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void protectedEndpoint_returns403_whenTokenIsValidButMissingRequiredRole() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + mintToken("some-user-id"));

        ResponseEntity<String> response = callProtectedEndpoint(headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void publicEndpoint_bypassesTheFilter_withoutAnyAuthorizationHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("""
                {"username":"","password":"Password123","email":"jane@example.com"}
                """, headers);

        ResponseEntity<String> response =
                restTemplate.postForEntity("/users/create", request, String.class);

        // No JWT was sent. 401 would mean the /users/create matcher in JwtAuthenticationFilter's
        // PUBLIC_ENDPOINTS regressed; 400 proves the request reached UserController's validation instead.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
