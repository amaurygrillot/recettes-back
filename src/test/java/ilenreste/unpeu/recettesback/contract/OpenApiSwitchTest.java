package ilenreste.unpeu.recettesback.contract;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Swagger UI and {@code /v3/api-docs} are a development aid, behind {@code SPRINGDOC_ENABLED}. The routes are
 * {@code permitAll()} in {@code SecurityFilterConfig} whatever the switch says, so what these prove is that with
 * it off they answer 404 because springdoc is not serving them - not 200, and not 401 from a locked door - and
 * that the committed default is off, so a deployment that never heard of the switch publishes nothing.
 * <p>
 * The committed {@code openapi.yaml} is the artifact either way; see {@code docs/openapi.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "SPRINGDOC_ENABLED=false")
@AutoConfigureTestRestTemplate
class OpenApiSwitchTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void withTheSwitchOffNeitherTheSpecNorSwaggerUiIsServed() {
        for (String path : new String[]{"/v3/api-docs", "/swagger-ui.html", "/swagger-ui/index.html"}) {
            assertThat(restTemplate.getForEntity(path, String.class).getStatusCode())
                    .as("GET %s with SPRINGDOC_ENABLED=false", path)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    /**
     * Read from the file and not from the environment: a developer's {@code .env} legitimately turns the switch
     * on, and this is about what a deployment gets when nobody sets it.
     */
    @Test
    void theCommittedDefaultIsOff() throws IOException {
        Properties committed = PropertiesLoaderUtils.loadAllProperties("application.properties");

        assertThat(committed.getProperty("springdoc.api-docs.enabled")).isEqualTo("${SPRINGDOC_ENABLED:false}");
        assertThat(committed.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("${SPRINGDOC_ENABLED:false}");
    }
}
