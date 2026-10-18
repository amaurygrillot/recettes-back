package ilenreste.unpeu.recettesback.contract;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.StreamSupport;

/**
 * Reduces an OpenAPI document to a set of one-line facts, so two documents can be compared
 * by set difference and a failure names exactly what differs.
 * <p>
 * The committed {@code openapi.yaml} and the {@code /v3/api-docs} the running application serves
 * are written very differently - one by hand, with {@code $ref}s into {@code components}; the
 * other by springdoc, reflecting on annotations and records. Comparing them structurally would
 * fail on every incidental difference (key order, descriptions, examples, where a parameter is
 * declared). Facts keep only what a client depends on: which operations exist, how they are
 * secured, what they take and which statuses and media types they answer, and the shape of each
 * schema. Descriptions, summaries, examples and constraints such as {@code maxLength} are left
 * out on purpose; they are prose and bean-validation detail, not wire shape.
 */
final class OpenApiFacts {

    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options");

    /**
     * Mapped onto the framework's own {@code ProblemDetail}, which Jackson flattens with a mixin
     * that springdoc's reflection does not see: the served schema shows a {@code properties}
     * member the wire never has. The wire shape is documented in the YAML and guarded by the
     * integration tests that assert on real error bodies.
     */
    private static final Set<String> SCHEMAS_NOT_COMPARED = Set.of("ProblemDetail");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JsonNode root;
    private final SortedSet<String> facts = new TreeSet<>();

    private OpenApiFacts(JsonNode root) {
        this.root = root;
    }

    static SortedSet<String> ofCommittedContract() {
        return of(OpenApiContract.load());
    }

    static SortedSet<String> ofServedSpec(String json) {
        return of(JSON.readTree(json));
    }

    static SortedSet<String> of(JsonNode spec) {
        OpenApiFacts extractor = new OpenApiFacts(spec);
        extractor.header();
        extractor.operations();
        extractor.schemas();
        return extractor.facts;
    }

    // -------------------------------------------------------------------------------------- header

    private void header() {
        JsonNode info = root.path("info");
        facts.add("info title=%s version=%s".formatted(info.path("title").asString(), info.path("version").asString()));
        root.path("tags").forEach(tag -> facts.add("tag " + tag.path("name").asString()));
        root.path("components").path("securitySchemes").properties().forEach(entry -> {
            JsonNode scheme = entry.getValue();
            facts.add("securityScheme %s type=%s scheme=%s bearerFormat=%s".formatted(entry.getKey(),
                    scheme.path("type").asString(), scheme.path("scheme").asString(),
                    scheme.path("bearerFormat").asString()));
        });
    }

    // ----------------------------------------------------------------------------------- operations

    private void operations() {
        root.path("paths").properties().forEach(path -> {
            JsonNode pathItem = path.getValue();
            pathItem.properties().stream()
                    .filter(entry -> HTTP_METHODS.contains(entry.getKey()))
                    .forEach(entry -> operation(entry.getKey().toUpperCase() + " " + path.getKey(),
                            entry.getValue(), pathItem.path("parameters")));
        });
    }

    private void operation(String name, JsonNode operation, JsonNode pathLevelParameters) {
        facts.add("%s operationId=%s".formatted(name, operation.path("operationId").asString()));
        facts.add("%s tags=%s".formatted(name, sorted(operation.path("tags"))));
        facts.add("%s access=%s".formatted(name, access(operation.path("security"))));

        List<JsonNode> parameters = new ArrayList<>();
        pathLevelParameters.forEach(parameters::add);
        operation.path("parameters").forEach(parameters::add);
        parameters.stream().map(this::resolve).forEach(parameter -> facts.add(
                "%s parameter %s:%s %s %s".formatted(name, parameter.path("in").asString(),
                        parameter.path("name").asString(),
                        parameter.path("required").asBoolean(false) ? "required" : "optional",
                        typeOf(parameter.path("schema")))));

        JsonNode requestBody = resolve(operation.path("requestBody"));
        requestBody.path("content").properties().forEach(content -> facts.add(
                "%s requestBody %s %s %s".formatted(name,
                        requestBody.path("required").asBoolean(false) ? "required" : "optional",
                        content.getKey(), typeOf(content.getValue().path("schema")))));

        operation.path("responses").properties().forEach(entry -> {
            JsonNode response = resolve(entry.getValue());
            if (response.path("content").isEmpty()) {
                facts.add("%s response %s no-content".formatted(name, entry.getKey()));
            }
            response.path("content").properties().forEach(content -> facts.add(
                    "%s response %s %s %s".formatted(name, entry.getKey(), content.getKey(),
                            typeOf(content.getValue().path("schema")))));
        });
    }

    /** No security requirement, or an empty one, is a public operation. */
    private static String access(JsonNode security) {
        List<String> schemes = new ArrayList<>();
        security.forEach(requirement -> requirement.propertyNames().forEach(schemes::add));
        return schemes.isEmpty() ? "public" : String.join("+", schemes.stream().sorted().toList());
    }

    // --------------------------------------------------------------------------------------- schemas

    private void schemas() {
        root.path("components").path("schemas").properties().stream()
                .filter(schema -> !SCHEMAS_NOT_COMPARED.contains(schema.getKey()))
                .forEach(schema -> {
                    String name = schema.getKey();
                    facts.add("schema " + name);
                    facts.add("schema %s required=%s".formatted(name, sorted(schema.getValue().path("required"))));
                    schema.getValue().path("properties").properties().forEach(property -> facts.add(
                            "schema %s.%s %s".formatted(name, property.getKey(), typeOf(property.getValue()))));
                });
    }

    // ---------------------------------------------------------------------------------------- helpers

    /** A short, comparable spelling of a schema: {@code string}, {@code integer:int32}, {@code $Tag}, {@code array<$Tag>}. */
    private String typeOf(JsonNode schema) {
        if (schema.isMissingNode() || schema.isNull()) {
            return "none";
        }
        if (schema.has("$ref")) {
            String reference = schema.path("$ref").asString();
            return "$" + reference.substring(reference.lastIndexOf('/') + 1);
        }
        if (schema.path("allOf").size() == 1) {
            return typeOf(schema.path("allOf").get(0));
        }
        String type = schema.path("type").asString("object");
        if ("array".equals(type)) {
            return "array<" + typeOf(schema.path("items")) + ">";
        }
        String format = schema.has("format") ? ":" + schema.path("format").asString() : "";
        String values = schema.has("enum") ? "{" + String.join("|", sorted(schema.path("enum"))) + "}" : "";
        return type + format + values;
    }

    /** Follows a {@code #/...} reference; a node that is not a reference is returned as it is. */
    private JsonNode resolve(JsonNode node) {
        if (!node.has("$ref")) {
            return node;
        }
        String reference = node.path("$ref").asString();
        return resolve(root.at(reference.substring(1)));
    }

    private static List<String> sorted(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asString).sorted().toList();
    }
}
