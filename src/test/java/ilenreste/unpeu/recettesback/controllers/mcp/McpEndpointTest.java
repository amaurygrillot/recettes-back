package ilenreste.unpeu.recettesback.controllers.mcp;

import ilenreste.unpeu.recettesback.repositories.users.RolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UserRolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
import ilenreste.unpeu.recettesback.support.TestAccount;
import ilenreste.unpeu.recettesback.repositories.reference.IngredientsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP endpoint through the real security filter chain and the real MCP server.
 * <p>
 * Two things are worth pinning: that {@code /mcp} is closed to everyone but an administrator
 * (anonymous gets 401, a plain user 403, read tools included, since a careless
 * {@code permitAll} or a missing rule would open the write tools), and that the tools are actually
 * registered and run under the caller's identity.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class McpEndpointTest {

    private static final String LIST_TOOLS = """
            {"jsonrpc":"2.0","id":1,"method":"tools/list"}""";
    private static final String CALL_LIST_UNITS = """
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_units","arguments":{}}}""";
    private static final String CALL_CREATE_EMPTY_RECIPE = """
            {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"create_recipe",
            "arguments":{"recipe":{"title":"","categoryIds":[],"ingredientGroups":[],"steps":[]}}}}""";

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private UsersRepository usersRepository;
    @Autowired
    private RolesRepository rolesRepository;
    @Autowired
    private UserRolesRepository userRolesRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private IngredientsRepository ingredientsRepository;

    private final JsonMapper json = JsonMapper.builder().build();

    private TestAccount account;

    @BeforeEach
    void setUp() {
        account = TestAccount.create(usersRepository, rolesRepository, userRolesRepository, passwordEncoder, "USER", "ADMIN");
    }

    @AfterEach
    void tearDown() {
        account.close();
    }

    @Test
    void anonymousCallerIsAnsweredUnauthorized() {
        ResponseEntity<String> response = post(LIST_TOOLS, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void authenticatedCallerSeesEveryTool() {
        ResponseEntity<String> response = post(LIST_TOOLS, account.bearerToken(restTemplate));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("search_recipes", "get_recipe", "create_recipe", "update_recipe",
                "delete_recipe", "list_categories", "list_tags", "list_units", "search_ingredients",
                "create_ingredient");
    }

    @Test
    void createRecipeDescribesItsIntroductionByItsTextAlone() {
        // RecipeIntroductionRequest.isBlank() looks like a bean getter. It is @JsonIgnore'd so the published
        // OpenAPI schema does not list a "blank" property; this pins that the MCP tool schema, built by a different
        // generator, never listed one either, so that annotation changes nothing for an MCP client.
        JsonNode tools = json.readTree(post(LIST_TOOLS, account.bearerToken(restTemplate)).getBody())
                .at("/result/tools");
        JsonNode createRecipe = null;
        for (JsonNode tool : tools) {
            if ("create_recipe".equals(tool.get("name").asString())) {
                createRecipe = tool;
            }
        }

        assertThat(createRecipe).isNotNull();
        assertThat(createRecipe.at("/inputSchema/properties/recipe/properties/introduction/properties")
                .propertyNames()).containsExactly("text");
    }

    @Test
    void toolCallRunsAgainstTheService() {
        ResponseEntity<String> response = post(CALL_LIST_UNITS, account.bearerToken(restTemplate));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"id\":2").doesNotContain("\"isError\":true");
    }

    @Test
    void invalidPayloadIsReportedAsToolErrorRatherThanSaved() {
        ResponseEntity<String> response = post(CALL_CREATE_EMPTY_RECIPE, account.bearerToken(restTemplate));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"isError\":true");
    }

    @Test
    void nonAdminCallerIsAnsweredForbiddenEvenOnReadTools() {
        try (TestAccount user = TestAccount.create(usersRepository, rolesRepository, userRolesRepository,
                passwordEncoder, "USER")) {
            ResponseEntity<String> response = post(CALL_LIST_UNITS, user.bearerToken(restTemplate));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void adminCanCurateReferenceData() {
        String token = account.bearerToken(restTemplate);
        String tagId = call("create_tag", "{\"tag\":{\"name\":\"mcp-tag-" + UUID.randomUUID() + "\"}}", token)
                .get("id").asString();

        assertThat(callRaw("delete_tag", "{\"id\":\"" + tagId + "\"}", token)).doesNotContain("\"isError\":true");
    }

    @Test
    void recipeLifecycleThroughTools() {
        String token = account.bearerToken(restTemplate);
        String categoryId = call("list_categories", "{}", token).get(0).get("id").asString();
        String ingredientName = "mcp-test-" + UUID.randomUUID().toString().substring(0, 8);
        String ingredientId = call("create_ingredient", "{\"ingredient\":{\"name\":\"" + ingredientName + "\"}}", token)
                .get("id").asString();
        String recipeId = null;
        try {
            recipeId = call("create_recipe", """
                    {"recipe":{"title":"Mcp tarte","categoryIds":["%s"],
                    "ingredientGroups":[{"ingredients":[{"ingredientId":"%s","quantity":2}]}],
                    "steps":[{"instruction":"Mix"}]}}""".formatted(categoryId, ingredientId), token)
                    .get("id").asString();

            assertThat(call("get_recipe", "{\"id\":\"" + recipeId + "\"}", token).get("title").asString())
                    .isEqualTo("Mcp tarte");
            assertThat(call("update_recipe", "{\"id\":\"" + recipeId + "\",\"changes\":{\"title\":\"Mcp tourte\"}}", token)
                    .get("title").asString()).isEqualTo("Mcp tourte");
            assertThat(call("search_recipes", "{\"criteria\":{\"title\":\"Mcp tourte\"}}", token).toString())
                    .contains(recipeId);
            assertThat(call("search_ingredients", "{\"namePrefix\":\"" + ingredientName + "\"}", token).toString())
                    .contains(ingredientId);
        } finally {
            if (recipeId != null) {
                callRaw("delete_recipe", "{\"id\":\"" + recipeId + "\"}", token);
            }
            ingredientsRepository.deleteById(ingredientId);
        }
        assertThat(callRaw("get_recipe", "{\"id\":\"" + recipeId + "\"}", token)).contains("\"isError\":true");
    }

    /** The decoded JSON a tool returned, failing the test if it reported an error. */
    private JsonNode call(String tool, String arguments, String token) {
        String body = callRaw(tool, arguments, token);
        assertThat(body).as("tool %s", tool).doesNotContain("\"isError\":true");
        JsonNode content = json.readTree(body).at("/result/content/0/text");
        return json.readTree(content.asString());
    }

    private String callRaw(String tool, String arguments, String token) {
        return post("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool + "\",\"arguments\":" + arguments + "}}", token).getBody();
    }

    private ResponseEntity<String> post(String jsonRpc, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
        if (bearerToken != null) {
            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        }
        return restTemplate.postForEntity("/mcp", new HttpEntity<>(jsonRpc, headers), String.class);
    }
}
