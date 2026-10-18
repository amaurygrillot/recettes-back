package ilenreste.unpeu.recettesback.contract;

import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The committed {@code openapi.yaml}, read from the classpath exactly as
 * {@code OpenApiConfig} reads it, and the list of operations it declares.
 */
final class OpenApiContract {

    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options");

    private OpenApiContract() {
    }

    /**
     * One operation as the contract declares it: where it is, who may call it, and what a request
     * to it has to carry.
     *
     * @param requiredRole the {@code x-required-role} extension, or {@code null} when any
     *                     authenticated caller will do (or the operation is public)
     * @param bodyType     the media type of the request body, or {@code null} when it has none
     */
    record Operation(String method, String path, boolean isPublic, String requiredRole, String bodyType) {

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    static JsonNode load() {
        YamlMapFactoryBean yaml = new YamlMapFactoryBean();
        yaml.setResources(new ClassPathResource("openapi/openapi.yaml"));
        Map<String, Object> contract = yaml.getObject();
        return JsonMapper.builder().build().valueToTree(contract);
    }

    static List<Operation> operations() {
        List<Operation> operations = new ArrayList<>();
        load().path("paths").properties().forEach(path -> path.getValue().properties().stream()
                .filter(entry -> HTTP_METHODS.contains(entry.getKey()))
                .forEach(entry -> operations.add(operation(entry.getKey(), path.getKey(), entry.getValue()))));
        return operations;
    }

    private static Operation operation(String method, String path, JsonNode operation) {
        String bodyType = operation.path("requestBody").path("content").propertyNames().stream()
                .findFirst().orElse(null);
        return new Operation(method.toUpperCase(), path, operation.path("security").isEmpty(),
                operation.path("x-required-role").isMissingNode() ? null : operation.path("x-required-role").asString(),
                bodyType);
    }
}
