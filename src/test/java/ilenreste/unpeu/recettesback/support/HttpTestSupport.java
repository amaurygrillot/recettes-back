package ilenreste.unpeu.recettesback.support;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.UUID;

/**
 * The three helpers every {@code @SpringBootTest} in this project needs to drive
 * real HTTP, in one place instead of copied into each suite.
 * <p>
 * They were re-typed in {@code RecipesIntegrationTest},
 * {@code ReferenceEndpointsIntegrationTest}, {@code MediaUploadIntegrationTest}
 * and {@code OptionalAuthenticationTest} — identically, except that {@code idOf}
 * had already drifted into two signatures taking different argument types. Lives
 * next to {@link TestAccount}, which is already the shared home for exactly this
 * kind of code.
 */
public final class HttpTestSupport {

    private HttpTestSupport() {
    }

    /** A JSON request body, optionally bearing an {@code Authorization} header. */
    public static HttpEntity<String> body(String json, String authorization) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (authorization != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        return new HttpEntity<>(json, headers);
    }

    /**
     * The {@code id} of the object a response body <em>starts</em> with.
     * <p>
     * A deliberate scan rather than a JSON parse, because these suites assert on
     * raw bodies throughout. Note the limitation the duplicated copies shared: it
     * finds the <strong>first</strong> {@code "id":} in the document, so it is
     * only correct for a response whose own id precedes any nested object's. That
     * holds for every creation response here (id is the first field), and a
     * response that ever changes shape will fail loudly on a wrong id rather than
     * quietly — but it is why this is a test helper and not a parser.
     */
    public static String idOf(String json) {
        int start = json.indexOf("\"id\":\"") + 6;
        return json.substring(start, json.indexOf('"', start));
    }

    /**
     * A name no other run can collide with.
     * <p>
     * These suites create real rows over real HTTP with no transaction to roll
     * back, and the reference tables carry a unique constraint on the normalized
     * name, so a fixed literal passes once and then 409s forever.
     */
    public static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
