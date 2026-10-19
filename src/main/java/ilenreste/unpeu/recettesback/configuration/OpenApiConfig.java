package ilenreste.unpeu.recettesback.configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import lombok.extern.log4j.Log4j2;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 *   <li>{@link #servedSpecCustomizer} corrects what the generated annotations and
 *       springdoc get wrong between them, so that Swagger UI does not mislead.</li>
 * </ul>
 */
@Log4j2
@Configuration
// Only when springdoc itself is on: otherwise nothing would read what this builds. matchIfMissing mirrors springdoc's
// own default, so removing the property cannot leave springdoc serving a spec without its header.
@ConditionalOnProperty(prefix = "springdoc.api-docs", name = "enabled", havingValue = "true", matchIfMissing = true)
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
                        .title(text(info, "title"))
                        .version(text(info, "version"))
                        .description(text(info, "description")))
                .components(components);
    }

    /**
     * Corrections to the served spec, for what the generated annotations and springdoc get wrong between them.
     * <p>
     * <strong>Media types.</strong> The generator writes every response of an
     * operation with the operation's <em>whole</em> list of media types, so a 201
     * claims to be {@code application/problem+json} and a 404 claims to be
     * {@code application/json}. Every error in this API is a problem document and
     * nothing else is, so a 4xx or 5xx keeps only that type and anything else drops
     * it.
     * <p>
     * <strong>Wildcard.</strong> The generated mappings have no {@code produces} (the
     * {@code strip-generated-media-type-conditions} step in {@code pom.xml}), and neither do the two hand-written searches, so springdoc has no
     * media type to give their body and writes {@code *}{@code /*}; it is JSON.
     * <p>
     * <strong>Not modified.</strong> springdoc copies the return type onto any response with no content of its
     * own, but a 304 never has a body.
     * <p>
     * <strong>Multipart.</strong> With no {@code consumes} springdoc calls a multipart body JSON, and it marks the
     * body optional whatever its parts say.
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
                correctMultipartBody(operation);
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
        relabelWildcardJsonBody(content);
        boolean error = status.startsWith("4") || status.startsWith("5");
        content.keySet().removeIf(mediaType -> error != PROBLEM_JSON.equals(mediaType));
    }

    /**
     * With no {@code produces} on a mapping, springdoc labels its body {@code *}{@code /*}. Only a body shaped like JSON
     * (an object, an array or a reference to one) is relabelled, and never over an {@code application/json} entry
     * that is already there: a text or binary body keeps the wildcard rather than being advertised as JSON.
     */
    private static void relabelWildcardJsonBody(Content content) {
        io.swagger.v3.oas.models.media.MediaType wildcard = content.get(MediaType.ALL_VALUE);
        if (wildcard != null && isJsonShaped(wildcard.getSchema())) {
            content.remove(MediaType.ALL_VALUE);
            content.putIfAbsent(MediaType.APPLICATION_JSON_VALUE, wildcard);
        }
    }

    private static boolean isJsonShaped(Schema<?> schema) {
        return schema != null && (schema.get$ref() != null || "object".equals(schema.getType())
                || "array".equals(schema.getType()));
    }

    /**
     * Without a {@code consumes} on the mapping (see the pom) springdoc cannot tell a multipart body from a JSON
     * one, and labels it {@code application/json}. A body with a binary part cannot be JSON, so it is relabelled
     * {@code multipart/form-data}; and springdoc marks a multipart body optional whatever its parts say, where a
     * missing required part is a 400, so a body whose schema has required parts is required.
     */
    private static void correctMultipartBody(Operation operation) {
        RequestBody body = operation.getRequestBody();
        if (body == null || body.getContent() == null) {
            return;
        }
        Content content = body.getContent();
        io.swagger.v3.oas.models.media.MediaType json = content.get(MediaType.APPLICATION_JSON_VALUE);
        if (json != null && hasBinaryPart(json.getSchema())) {
            content.remove(MediaType.APPLICATION_JSON_VALUE);
            content.putIfAbsent(MediaType.MULTIPART_FORM_DATA_VALUE, json);
        }
        io.swagger.v3.oas.models.media.MediaType multipart = content.get(MediaType.MULTIPART_FORM_DATA_VALUE);
        if (multipart != null && multipart.getSchema() != null && multipart.getSchema().getRequired() != null
                && !multipart.getSchema().getRequired().isEmpty()) {
            body.setRequired(true);
        }
    }

    private static boolean hasBinaryPart(Schema<?> schema) {
        return schema != null && schema.getProperties() != null && schema.getProperties().values().stream()
                .anyMatch(property -> "binary".equals(property.getFormat()));
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
                .scheme(text(scheme, "scheme"))
                .bearerFormat(text(scheme, "bearerFormat"))
                .description(text(scheme, "description"));
    }

    /** A scalar as text: YAML reads {@code version: 1.0} as a number, and the header must not depend on how it was typed. */
    private static String text(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        return value == null ? null : value.toString();
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
