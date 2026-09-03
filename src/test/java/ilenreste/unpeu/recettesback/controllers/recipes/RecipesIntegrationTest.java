package ilenreste.unpeu.recettesback.controllers.recipes;

import ilenreste.unpeu.recettesback.repositories.recipes.RecipesRepository;
import ilenreste.unpeu.recettesback.repositories.reference.CategoriesRepository;
import ilenreste.unpeu.recettesback.repositories.reference.IngredientsRepository;
import ilenreste.unpeu.recettesback.repositories.reference.TagsRepository;
import ilenreste.unpeu.recettesback.repositories.users.RolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UserRolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
import ilenreste.unpeu.recettesback.support.TestAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recipes end to end. Carries the regressions the design calls out as ones that will not happen by
 * accident: step order surviving a round trip, and the absent-versus-empty pair on update.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class RecipesIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private UsersRepository usersRepository;
    @Autowired
    private RolesRepository rolesRepository;
    @Autowired
    private UserRolesRepository userRolesRepository;
    @Autowired
    private CategoriesRepository categoriesRepository;
    @Autowired
    private TagsRepository tagsRepository;
    @Autowired
    private IngredientsRepository ingredientsRepository;
    @Autowired
    private RecipesRepository recipesRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private TestAccount author;
    private TestAccount otherUser;
    private TestAccount admin;
    private String authorToken;
    private String otherToken;
    private String adminToken;

    private String categoryId;
    private String secondCategoryId;
    private String tagId;
    private String ingredientId;
    private final List<String> createdRecipeIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        author = TestAccount.create(usersRepository, rolesRepository, userRolesRepository,
                passwordEncoder, "USER");
        otherUser = TestAccount.create(usersRepository, rolesRepository, userRolesRepository,
                passwordEncoder, "USER");
        admin = TestAccount.create(usersRepository, rolesRepository, userRolesRepository,
                passwordEncoder, "USER", "ADMIN");
        authorToken = author.bearerToken(restTemplate);
        otherToken = otherUser.bearerToken(restTemplate);
        adminToken = admin.bearerToken(restTemplate);

        categoryId = idOf(post("/categories", "{\"name\":\"%s\"}".formatted(unique("dessert")), adminToken,
                HttpStatus.CREATED));
        secondCategoryId = idOf(post("/categories", "{\"name\":\"%s\"}".formatted(unique("gouter")), adminToken,
                HttpStatus.CREATED));
        tagId = idOf(post("/tags", "{\"name\":\"%s\"}".formatted(unique("rapide")), adminToken,
                HttpStatus.CREATED));
        ingredientId = idOf(post("/ingredients", "{\"name\":\"%s\"}".formatted(unique("farine")), authorToken,
                HttpStatus.CREATED));
    }

    @AfterEach
    void tearDown() {
        createdRecipeIds.forEach(id -> recipesRepository.findById(id)
                .ifPresent(recipesRepository::delete));
        ingredientsRepository.deleteById(ingredientId);
        tagsRepository.deleteById(tagId);
        categoriesRepository.deleteById(categoryId);
        categoriesRepository.deleteById(secondCategoryId);
        admin.close();
        otherUser.close();
        author.close();
    }

    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private HttpEntity<String> body(String json, String authorization) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (authorization != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        return new HttpEntity<>(json, headers);
    }

    private ResponseEntity<String> post(String path, String json, String token, HttpStatus expected) {
        ResponseEntity<String> response = restTemplate.postForEntity(path, body(json, token), String.class);
        assertThat(response.getStatusCode()).as("POST %s -> %s", path, response.getBody()).isEqualTo(expected);
        return response;
    }

    private ResponseEntity<String> put(String path, String json, String token) {
        return restTemplate.exchange(path, HttpMethod.PUT, body(json, token), String.class);
    }

    private String idOf(ResponseEntity<String> response) {
        String json = response.getBody();
        int start = json.indexOf("\"id\":\"") + 6;
        return json.substring(start, json.indexOf('"', start));
    }

    private String minimalRecipeJson(String title) {
        return """
                {
                  "title": "%s",
                  "categoryIds": ["%s"],
                  "tagIds": ["%s"],
                  "ingredientGroups": [
                    {"ingredients": [{"ingredientId": "%s", "quantity": 200.5}]}
                  ],
                  "steps": [{"instruction": "Melanger"}]
                }
                """.formatted(title, categoryId, tagId, ingredientId);
    }

    private String createRecipe(String json, String token) {
        String id = idOf(post("/recipes", json, token, HttpStatus.CREATED));
        createdRecipeIds.add(id);
        return id;
    }

    @Test
    void createsAndReadsBackAWholeRecipe() {
        String id = createRecipe(minimalRecipeJson("Tarte aux pommes"), authorToken);

        // Public read: no Authorization header at all.
        ResponseEntity<String> read = restTemplate.getForEntity("/recipes/" + id, String.class);

        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody())
                .contains("Tarte aux pommes")
                .contains("Melanger")
                // The author is stamped from the token, never taken from the body.
                .contains(author.username())
                .contains("200.5");
    }

    /**
     * The regression a one-step recipe can never catch.
     * <p>
     * Collections are mapped as Set to avoid bag fetches, and a Set is unordered, so only
     * {@code @OrderBy("position")} puts the ORDER BY into the SQL. containsExactly is what makes
     * this meaningful: a set-based or size-based assertion passes on a shuffle.
     */
    @Test
    void returnsFiveStepsInTheOrderTheyWereSent() {
        List<String> instructions = List.of(
                "Prechauffer le four", "Etaler la pate", "Couper les pommes",
                "Disposer les pommes", "Enfourner 40 min");
        String steps = instructions.stream()
                .map(instruction -> "{\"instruction\": \"%s\"}".formatted(instruction))
                .reduce((a, b) -> a + "," + b).orElseThrow();

        String id = createRecipe("""
                {
                  "title": "Tarte",
                  "categoryIds": ["%s"],
                  "steps": [%s]
                }
                """.formatted(categoryId, steps), authorToken);

        String read = restTemplate.getForObject("/recipes/" + id, String.class);

        List<Integer> positions = instructions.stream().map(read::indexOf).toList();
        assertThat(positions).doesNotContain(-1);
        assertThat(positions).isSorted();
    }

    /**
     * Absent means "don't touch". Half of the pair with the test below; a suite that asserts only
     * one of them will not notice if the rule inverts.
     */
    @Test
    void updatingOnlyTheTitleLeavesTheCategoriesAlone() {
        String id = createRecipe(minimalRecipeJson("Tarte"), authorToken);

        ResponseEntity<String> updated = put("/recipes/" + id, "{\"title\": \"Tarte renommee\"}", authorToken);

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).contains("Tarte renommee").contains(categoryId);
    }

    /** The other half: empty is a different request from absent, and it is refused. */
    @Test
    void updatingWithAnEmptyCategoryListIsRejected_andChangesNothing() {
        String id = createRecipe(minimalRecipeJson("Tarte"), authorToken);

        ResponseEntity<String> updated = put("/recipes/" + id, "{\"categoryIds\": []}", authorToken);

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // The invariant everything else leans on: every recipe stays browsable.
        assertThat(restTemplate.getForObject("/recipes/" + id, String.class)).contains(categoryId);
    }

    @Test
    void replacingTheCategoryListSwapsItWholesale() {
        String id = createRecipe(minimalRecipeJson("Tarte"), authorToken);

        ResponseEntity<String> updated = put("/recipes/" + id,
                "{\"categoryIds\": [\"%s\"]}".formatted(secondCategoryId), authorToken);

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).contains(secondCategoryId).doesNotContain(categoryId);
    }

    @Test
    void clearingEveryTagIsAllowed_unlikeClearingEveryCategory() {
        String id = createRecipe(minimalRecipeJson("Tarte"), authorToken);

        ResponseEntity<String> updated = put("/recipes/" + id, "{\"tagIds\": []}", authorToken);

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).doesNotContain(tagId);
    }

    @Test
    void rejectsABodyReferencingSomethingThatDoesNotExist() {
        // 400 and not 404: /recipes is perfectly reachable, it is the payload that is wrong. The
        // detail names the field and the missing id so the client can act on it.
        ResponseEntity<String> response = restTemplate.postForEntity("/recipes", body("""
                {"title": "Tarte", "categoryIds": ["00000000-0000-0000-0000-000000000000"]}
                """, authorToken), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("categoryIds");
    }

    @Test
    void rejectsARecipeWithNoCategoryAtAll() {
        ResponseEntity<String> response = restTemplate.postForEntity("/recipes",
                body("{\"title\": \"Tarte\", \"categoryIds\": []}", authorToken), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsAStepWithABlankInstruction() {
        // Guarded by @NotBlank on the child record. Without it this reaches
        // recipe_steps.instruction NOT NULL and surfaces as a generic 500 instead.
        ResponseEntity<String> response = restTemplate.postForEntity("/recipes", body("""
                {"title": "Tarte", "categoryIds": ["%s"], "steps": [{"instruction": "  "}]}
                """.formatted(categoryId), authorToken), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void writingNeedsALogin_butReadingDoesNot() {
        assertThat(restTemplate.getForEntity("/recipes", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // 401, not 403 - the regression the explicit AuthenticationEntryPoint guards.
        assertThat(restTemplate.postForEntity("/recipes",
                body(minimalRecipeJson("Tarte"), null), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void onlyTheAuthorOrAnAdminMayEditARecipe() {
        String id = createRecipe(minimalRecipeJson("Tarte"), authorToken);

        // A different signed-in user: authenticated, but not permitted. This rule depends on a row
        // in the database, so no URL matcher could express it.
        assertThat(put("/recipes/" + id, "{\"title\": \"Hijacked\"}", otherToken).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(put("/recipes/" + id, "{\"title\": \"Tidied by an admin\"}", adminToken).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void onlyTheAuthorOrAnAdminMayDeleteARecipe() {
        String id = createRecipe(minimalRecipeJson("Tarte"), authorToken);

        assertThat(restTemplate.exchange("/recipes/" + id, HttpMethod.DELETE,
                body(null, otherToken), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(restTemplate.exchange("/recipes/" + id, HttpMethod.DELETE,
                body(null, authorToken), String.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(restTemplate.getForEntity("/recipes/" + id, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void filtersTheListingAndCountsEachRecipeOnce() {
        String id = createRecipe("""
                {"title": "%s", "categoryIds": ["%s", "%s"]}
                """.formatted(unique("Tarte"), categoryId, secondCategoryId), authorToken);

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/recipes?categoryId=" + categoryId, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains(id);
        // In two categories, counted once: the duplicate-row bug the EXISTS query shape avoids.
        assertThat(countOccurrences(response.getBody(), id)).isEqualTo(1);
    }

    @Test
    void filtersByAuthorAndByTitleFragment() {
        String title = unique("Tarte aux poires");
        String id = createRecipe(minimalRecipeJson(title), authorToken);

        assertThat(restTemplate.getForObject("/recipes?authorId=" + author.id(), String.class))
                .contains(id);
        assertThat(restTemplate.getForObject("/recipes?q=" + title.toUpperCase(), String.class))
                .contains(id);
        assertThat(restTemplate.getForObject("/recipes?authorId=" + otherUser.id(), String.class))
                .doesNotContain(id);
    }

    @Test
    void unknownRecipeIsNotFound() {
        assertThat(restTemplate.getForEntity("/recipes/no-such-id", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
