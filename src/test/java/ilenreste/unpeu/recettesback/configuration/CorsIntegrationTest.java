package ilenreste.unpeu.recettesback.configuration;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CORS policy against the real filter chain (see docs/cors.md).
 * <p>
 * A {@code standaloneSetup} MockMvc test cannot see any of this: it has no filter chain, so there is no
 * {@code CorsFilter} to be ordered before authentication and nothing that could turn a preflight into a
 * 401. The allowed origin is pinned here rather than read from the environment, so the suite passes
 * whatever {@code CORS_ALLOWED_ORIGINS} the developer has in their local {@code .env}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.cors.allowed-origins=" + CorsIntegrationTest.ALLOWED_ORIGIN)
@AutoConfigureTestRestTemplate
class CorsIntegrationTest {

    static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final String OTHER_ORIGIN = "https://evil.example.com";

    /** Not a JWT at all, standing in for the expired tokens a restart leaves in every client. */
    private static final String STALE_TOKEN = "Bearer eyJhbGciOiJub25lIn0.e30.not-a-real-signature";

    @Autowired
    private TestRestTemplate restTemplate;

    private ResponseEntity<String> preflight(String path, String origin, String method, String headers) {
        HttpHeaders request = new HttpHeaders();
        request.set(HttpHeaders.ORIGIN, origin);
        request.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method);
        if (headers != null) {
            request.set(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, headers);
        }
        return restTemplate.exchange(path, HttpMethod.OPTIONS, new HttpEntity<>(request), String.class);
    }

    @Test
    void preflight_isAnswered200WithThePolicy_onAPublicRoute() {
        ResponseEntity<String> response = preflight("/users/create", ALLOWED_ORIGIN, "POST",
                "authorization, content-type, accept");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders headers = response.getHeaders();
        assertThat(headers.getAccessControlAllowOrigin()).isEqualTo(ALLOWED_ORIGIN);
        assertThat(headers.getAccessControlAllowMethods())
                .containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE,
                        HttpMethod.OPTIONS);
        // Spring answers with the requested headers it allows, spelled as the browser sent them.
        assertThat(headers.getAccessControlAllowHeaders())
                .containsExactlyInAnyOrder("authorization", "content-type", "accept");
        assertThat(headers.getAccessControlMaxAge()).isEqualTo(3600);
        // No cookies cross, so no credentials are allowed - and a wildcard origin is never echoed.
        assertThat(headers.getAccessControlAllowCredentials()).isFalse();
        assertThat(headers.getAccessControlAllowOrigin()).isNotEqualTo("*");
    }

    @Test
    void preflight_isAnswered200_onAProtectedRoute_whereTheBrowserCannotSendAToken() {
        // The browser never attaches Authorization to a preflight, so a protected route that asked for
        // one would be unreachable from any other origin. PUT /users/update needs the USER role.
        ResponseEntity<String> response = preflight("/users/update", ALLOWED_ORIGIN, "PUT",
                "authorization,content-type");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ALLOWED_ORIGIN);
        assertThat(response.getHeaders().getAccessControlAllowHeaders()).contains("authorization");
    }

    @Test
    void preflight_isAnswered200_onAnAdminOnlyRoute() {
        ResponseEntity<String> response = preflight("/tags/some-id", ALLOWED_ORIGIN, "DELETE", "authorization");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ALLOWED_ORIGIN);
    }

    /**
     * The regression this configuration exists to prevent: CorsFilter sits ahead of
     * JwtAuthenticationFilter, so a dead token on the request is never even looked at. Were the order
     * reversed, this would be the same 401 as {@code OptionalAuthenticationTest}'s broken-token case.
     */
    @Test
    void preflight_neverReachesTheJwtFilter_soAStaleTokenIsNotA401() {
        HttpHeaders request = new HttpHeaders();
        request.set(HttpHeaders.ORIGIN, ALLOWED_ORIGIN);
        request.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT");
        request.set(HttpHeaders.AUTHORIZATION, STALE_TOKEN);

        ResponseEntity<String> response = restTemplate.exchange("/users/update", HttpMethod.OPTIONS,
                new HttpEntity<>(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ALLOWED_ORIGIN);
    }

    @Test
    void preflight_isRefused_forAnOriginThatIsNotListed() {
        ResponseEntity<String> response = preflight("/users/create", OTHER_ORIGIN, "POST", "content-type");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    void preflight_isRefused_forAMethodThePolicyDoesNotAllow() {
        ResponseEntity<String> response = preflight("/recipes/some-id", ALLOWED_ORIGIN, "PATCH", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    void preflight_isRefused_forAHeaderThePolicyDoesNotAllow() {
        ResponseEntity<String> response = preflight("/users/create", ALLOWED_ORIGIN, "POST", "x-custom-header");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    void realRequest_fromTheAllowedOrigin_exposesLocationAndRetryAfter() {
        HttpHeaders request = new HttpHeaders();
        request.set(HttpHeaders.ORIGIN, ALLOWED_ORIGIN);

        ResponseEntity<String> response = restTemplate.exchange("/tags", HttpMethod.GET,
                new HttpEntity<>(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ALLOWED_ORIGIN);
        assertThat(response.getHeaders().getAccessControlExposeHeaders())
                .containsExactlyInAnyOrder(HttpHeaders.LOCATION, HttpHeaders.RETRY_AFTER);
        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isFalse();
    }

    /**
     * An error carries the policy too. Without it the browser hides a 401 or a 429 from the script
     * behind an opaque network error, and the front end could neither re-login nor honour Retry-After.
     */
    @Test
    void errorResponse_fromTheAllowedOrigin_isReadableByTheBrowser() {
        HttpHeaders request = new HttpHeaders();
        request.set(HttpHeaders.ORIGIN, ALLOWED_ORIGIN);

        ResponseEntity<String> response = restTemplate.exchange("/users/update", HttpMethod.PUT,
                new HttpEntity<>("{}", withJson(request)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ALLOWED_ORIGIN);
        assertThat(response.getHeaders().getAccessControlExposeHeaders()).contains(HttpHeaders.RETRY_AFTER);
    }

    @Test
    void realRequest_fromAnUnlistedOrigin_isRefused() {
        HttpHeaders request = new HttpHeaders();
        request.set(HttpHeaders.ORIGIN, OTHER_ORIGIN);

        ResponseEntity<String> response = restTemplate.exchange("/tags", HttpMethod.GET,
                new HttpEntity<>(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    void realRequest_withoutAnOrigin_isUntouched() {
        // Not a browser cross-origin call: curl, the MCP client, server-to-server. CORS is no business of theirs.
        ResponseEntity<String> response = restTemplate.getForEntity("/tags", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    private static HttpHeaders withJson(HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
