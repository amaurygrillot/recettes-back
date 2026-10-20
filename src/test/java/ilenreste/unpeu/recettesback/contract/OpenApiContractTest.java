package ilenreste.unpeu.recettesback.contract;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The drift check: the API the running application serves must be the API
 * {@code openapi.yaml} describes. See {@code docs/openapi.md}.
 * <p>
 * The YAML is the contract, but only part of it is enforced by construction. The generated
 * {@code *Api} interfaces make a controller fail to compile when an operation is added to the YAML
 * and not implemented; they cannot notice that a controller grew an extra endpoint, that a DTO record
 * gained a field the YAML does not list, that the two paged searches (written by hand, because the
 * generator cannot express a generic {@code Page}) stopped matching their entry, or that Spring Data
 * changed the JSON shape of a page. This test is what notices those.
 * <p>
 * A real {@code @SpringBootTest} on a random port, because the served document is built by springdoc
 * from the real handler mappings. {@code /v3/api-docs} is public, so no account is needed.
 * <p>
 * Both documents are first reduced to facts, see {@link OpenApiFacts}; a failure lists the facts in
 * one document and not the other, which reads as "the YAML says X, the application does Y".
 */
// Springdoc is off unless this switch is on (see application.properties); the test needs the served spec, and says so
// itself rather than relying on a developer's .env.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "SPRINGDOC_ENABLED=true")
@AutoConfigureTestRestTemplate
class OpenApiContractTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void everyOperationTheContractDescribesIsServedAsDescribed() {
        assertNoDrift(fact -> !fact.startsWith("schema "));
    }

    @Test
    void everySchemaTheContractDescribesMatchesTheRecordBehindIt() {
        assertNoDrift(fact -> fact.startsWith("schema "));
    }

    @Test
    void swaggerUiAndTheSpecAreReachableWithoutAToken() {
        assertThat(restTemplate.getForEntity("/v3/api-docs", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/swagger-ui/index.html", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private void assertNoDrift(Predicate<String> scope) {
        ResponseEntity<String> served = restTemplate.getForEntity("/v3/api-docs", String.class);
        assertThat(served.getStatusCode()).as("GET /v3/api-docs").isEqualTo(HttpStatus.OK);

        SortedSet<String> contract = filter(OpenApiFacts.ofCommittedContract(), scope);
        SortedSet<String> running = filter(OpenApiFacts.ofServedSpec(served.getBody()), scope);

        SortedSet<String> missing = new TreeSet<>(contract);
        missing.removeAll(running);
        SortedSet<String> unexpected = new TreeSet<>(running);
        unexpected.removeAll(contract);

        // Soft, so one run reports both directions: the two lists are usually two halves of one change.
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(missing)
                    .as("Described in openapi.yaml but not what the running application serves. "
                            + "Either a controller or a DTO record changed without the YAML, or the YAML "
                            + "changed without the code.")
                    .isEmpty();
            softly.assertThat(unexpected)
                    .as("Served by the running application but not described in openapi.yaml. "
                            + "Add it to the YAML (and see docs/openapi.md), or remove it from the code.")
                    .isEmpty();
        });
    }

    private static SortedSet<String> filter(SortedSet<String> facts, Predicate<String> scope) {
        SortedSet<String> kept = new TreeSet<>(facts);
        kept.removeIf(scope.negate());
        return kept;
    }
}
