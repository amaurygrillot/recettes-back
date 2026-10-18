package ilenreste.unpeu.recettesback.configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import lombok.extern.log4j.Log4j2;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;

import java.util.Map;

/**
 * What springdoc serves as {@code /v3/api-docs} and renders in Swagger UI: the
 * running application's own view of its API, which {@code OpenApiContractTest}
 * holds against the committed {@code openapi.yaml}. See {@code docs/openapi.md}.
 * <p>
 * Two beans, both there to make that served view <em>truthful</em> rather than to
 * author anything:
 * <ul>
 *   <li>{@link #contractHeader} gives it the YAML's own title, version, description
 *       and security schemes, read from the contract itself. Annotations on the
 *       generated interfaces describe operations, but there is no annotation for
 *       "this API authenticates with a bearer token", and repeating the header
 *       here by hand would be a second place for it to drift.</li>
 *   <li>{@link #servedSpecCustomizer} corrects two things the generated annotations
 *       and springdoc get wrong between them, so that Swagger UI does not mislead.</li>
 * </ul>
 */
@Log4j2
@Configuration
public class OpenApiConfig {

    private static final String CONTRACT = "openapi/openapi.yaml";
    private static final String PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE;
    private static final String NOT_MODIFIED = "304";

    @Bean
    OpenAPI contractHeader() {
        YamlMapFactoryBean yaml = new YamlMapFactoryBean();
        yaml.setResources(new ClassPathResource(CONTRACT));
        Map<String, Object> contract = yaml.getObject();
        if (contract == null) {
            throw new IllegalStateException("The API contract %s is empty or missing.".formatted(CONTRACT));
        }
        log.debug("Reading the OpenAPI header and security schemes from {}", CONTRACT);

        Map<String, Object> info = section(contract, "info");
        Components components = new Components();
        section(section(contract, "components"), "securitySchemes")
                .forEach((name, scheme) -> components.addSecuritySchemes(name, securityScheme(name, scheme)));
        return new OpenAPI()
                .info(new Info()
                        .title((String) info.get("title"))
                        .version((String) info.get("version"))
                        .description((String) info.get("description")))
                .components(components);
    }

    /**
     * Two corrections to the served spec.
     * <p>
     * <strong>Media types.</strong> The generator writes every response of an
     * operation with the operation's <em>whole</em> list of media types, so a 201
     * claims to be {@code application/problem+json} and a 404 claims to be
     * {@code application/json}. Every error in this API is a problem document and
     * nothing else is, so a 4xx or 5xx keeps only that type and anything else drops
     * it.
     * <p>
     * <strong>Paging.</strong> {@code Pageable} expands to {@code page}, {@code size}
     * and {@code sort}. The services read the first two and ignore the third &mdash;
     * ordering is {@code sortBy}/{@code direction}, a closed enum, deliberately not
     * a free-text property &mdash; so documenting {@code sort} would invite a
     * parameter that does nothing.
     */
    @Bean
    OpenApiCustomizer servedSpecCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                dropIgnoredSortParameter(operation);
                requireMultipartBodyWhenItHasRequiredParts(operation);
                if (operation.getResponses() != null) {
                    operation.getResponses().forEach(OpenApiConfig::keepOnlyTheMediaTypesThatApply);
                }
            }));
        };
    }

    private static void keepOnlyTheMediaTypesThatApply(String status, ApiResponse response) {
        Content content = response.getContent();
        if (content == null) {
            return;
        }
        if (NOT_MODIFIED.equals(status)) {
            // springdoc copies the return type onto any response that has no content of its own, but a 304
            // is "your copy is still current": it never has a body.
            response.setContent(null);
            return;
        }
        boolean error = status.startsWith("4") || status.startsWith("5");
        content.keySet().removeIf(mediaType -> error != PROBLEM_JSON.equals(mediaType));
    }

    /**
     * springdoc marks the body of a multipart operation optional whatever the parts say; a missing
     * required part is a 400, so the body is required.
     */
    private static void requireMultipartBodyWhenItHasRequiredParts(Operation operation) {
        RequestBody body = operation.getRequestBody();
        if (body == null || body.getContent() == null) {
            return;
        }
        io.swagger.v3.oas.models.media.MediaType multipart = body.getContent().get(MediaType.MULTIPART_FORM_DATA_VALUE);
        if (multipart != null && multipart.getSchema() != null && multipart.getSchema().getRequired() != null
                && !multipart.getSchema().getRequired().isEmpty()) {
            body.setRequired(true);
        }
    }

    private static void dropIgnoredSortParameter(Operation operation) {
        if (operation.getParameters() == null || !hasQueryParameter(operation, "page")
                || !hasQueryParameter(operation, "size")) {
            return;
        }
        operation.getParameters().removeIf(parameter -> isQueryParameter(parameter, "sort"));
    }

    private static boolean hasQueryParameter(Operation operation, String name) {
        return operation.getParameters().stream().anyMatch(parameter -> isQueryParameter(parameter, name));
    }

    private static boolean isQueryParameter(Parameter parameter, String name) {
        return "query".equals(parameter.getIn()) && name.equals(parameter.getName());
    }

    private static SecurityScheme securityScheme(String name, Object definition) {
        Map<String, Object> scheme = castToMap(definition);
        if (!"http".equals(scheme.get("type"))) {
            throw new IllegalStateException(
                    "Security scheme '%s' in %s is not an http scheme; teach OpenApiConfig about it.".formatted(name, CONTRACT));
        }
        return new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme((String) scheme.get("scheme"))
                .bearerFormat((String) scheme.get("bearerFormat"))
                .description((String) scheme.get("description"));
    }

    private static Map<String, Object> section(Map<String, Object> parent, String key) {
        return castToMap(parent.get(key));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("Expected a mapping in %s but found %s.".formatted(CONTRACT, value));
        }
        return (Map<String, Object>) map;
    }
}
