package ilenreste.unpeu.recettesback.controllers.recipes;

import ilenreste.unpeu.recettesback.repositories.recipes.RecipesRepository;
import ilenreste.unpeu.recettesback.repositories.reference.CategoriesRepository;
import ilenreste.unpeu.recettesback.repositories.reference.CategoryRelationsRepository;
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

import static ilenreste.unpeu.recettesback.support.HttpTestSupport.body;
import static ilenreste.unpeu.recettesback.support.HttpTestSupport.unique;
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
    private CategoryRelationsRepository categoryRelationsRepository;
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
    private String categoryName;
    private String secondCategoryId;
    private String tagId;
    private String tagName;
    private String ingredientId;
    private final List<String> createdRecipeIds = new ArrayList<>();
    /** Deleted before the fixture categories, whose relation rows they point at. */
    private final List<String> createdSubcategoryIds = new ArrayList<>();

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

        categoryName = unique("dessert");
        categoryId = idOf(post("/categories", "{\"name\":\"%s\"}".formatted(categoryName), adminToken,
                HttpStatus.CREATED));
        secondCategoryId = idOf(post("/categories", "{\"name\":\"%s\"}".formatted(unique("gouter")), adminToken,
                HttpStatus.CREATED));
        tagName = unique("rapide");
        tagId = idOf(post("/tags", "{\"name\":\"%s\"}".formatted(tagName), adminToken,
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
        // The relation row has to go first: every category has one, and a main category's points
        // at itself, so the foreign key outlives the category unless it is cleared by hand. The
        // endpoint does this in CategoryService.beforeDelete; a repository delete does not.
        // Children before parents, or the parent's delete trips the relation foreign key.
        createdSubcategoryIds.reversed().forEach(this::deleteCategory);
        deleteCategory(categoryId);
        deleteCategory(secondCategoryId);
        admin.close();
        otherUser.close();
        author.close();
    }

    private void deleteCategory(String id) {
        categoryRelationsRepository.deleteById(id);
        categoriesRepository.deleteById(id);
    }

    private ResponseEntity<String> post(String path, String json, String token, HttpStatus expected) {
        ResponseEntity<String> response = restTemplate.postForEntity(path, body(json, token), String.class);
        assertThat(response.getStatusCode()).as("POST %s -> %s", path, response.getBody()).isEqualTo(expected);
        return response;
    }

    private ResponseEntity<String> put(String path, String json, String token) {
        return restTemplate.exchange(path, HttpMethod.PUT, body(json, token), String.class);
    }

    /**
     * Replacing a non-empty collection with another non-empty one, which nothing
     * covered before: the only PUTs in this suite changed the title, set or cleared
     * the introduction, or sent an empty array.
     * <p>
     * That left the one path that both deletes and re-inserts the same
     * {@code (recipe_id, position)} keys unexercised. {@code replaceSteps} clears
     * the collection and re-adds children renumbered {@code 0..n-1}, against
     * {@code UNIQUE (recipe_id, position)} on {@code recipe_steps} — so it works
     * only because Hibernate flushes orphan-removal DELETEs before INSERTs
     * ({@code ActionQueue$OrderedActions} puts {@code OrphanRemovalAction} ahead of
     * {@code EntityInsertAction}). That is internal ordering we do not control, and
     * an upgrade that changed it would break every recipe edit with nothing to say
     * so. Hence a test that actually reuses the positions.
     */
    @Test
    void replacesANonEmptyStepListWithAnotherOne_reusingTheSamePositions() {
        String id = createRecipe("""
                {
                  "title": "%s",
                  "categoryIds": ["%s"],
                  "ingredientGroups": [
                    {"ingredients": [{"ingredientId": "%s", "quantity": 1}]}
                  ],
                  "steps": [
                    {"instruction": "Premiere etape"},
                    {"instruction": "Deuxieme etape"},
                    {"instruction": "Troisieme etape"}
                  ]
                }
                """.formatted(unique("Tarte a remplacer"), categoryId, ingredientId), authorToken);

        // Three steps at positions 0,1,2 become two at positions 0,1 - both of which already exist.
        ResponseEntity<String> response = put("/recipes/" + id, """
                {"steps": [{"instruction": "Nouvelle premiere"}, {"instruction": "Nouvelle deuxieme"}]}
                """, authorToken);

        assertThat(response.getStatusCode()).as("PUT -> %s", response.getBody()).isEqualTo(HttpStatus.OK);

        String read = restTemplate.getForObject("/recipes/" + id, String.class);
        assertThat(read).contains("Nouvelle premiere", "Nouvelle deuxieme");
        assertThat(read).doesNotContain("Premiere etape", "Deuxieme etape", "Troisieme etape");
        // In the order sent, which is what @OrderBy("position") plus the renumbering guarantees.
        assertThat(read.indexOf("Nouvelle premiere")).isLessThan(read.indexOf("Nouvelle deuxieme"));
    }

    /** The same reuse-the-positions case for the other two cascading collections. */
    @Test
    void replacesANonEmptyIngredientGroupListWithAnotherOne() {
        String id = createRecipe("""
                {
                  "title": "%s",
                  "categoryIds": ["%s"],
                  "ingredientGroups": [
                    {"title": "Pour la pate", "ingredients": [{"ingredientId": "%s", "quantity": 1}]},
                    {"title": "Pour la garniture", "ingredients": [{"ingredientId": "%s", "quantity": 2}]}
                  ],
                  "steps": [{"instruction": "Melanger"}]
                }
                """.formatted(unique("Tarte groupes"), categoryId, ingredientId, ingredientId), authorToken);

        ResponseEntity<String> response = put("/recipes/" + id, """
                {"ingredientGroups": [
                   {"title": "Tout ensemble", "ingredients": [{"ingredientId": "%s", "quantity": 3}]}
                 ]}
                """.formatted(ingredientId), authorToken);

        assertThat(response.getStatusCode()).as("PUT -> %s", response.getBody()).isEqualTo(HttpStatus.OK);

        String read = restTemplate.getForObject("/recipes/" + id, String.class);
        assertThat(read).contains("Tout ensemble");
        assertThat(read).doesNotContain("Pour la pate", "Pour la garniture");
    }

    /**
     * A blank id inside {@code categoryIds} used to pass validation and then
     * resolve to nothing, so the recipe was stored with no categories at all and
     * answered 201 - the exact invariant {@code @NotEmpty} is there to hold.
     * {@code ReferenceResolver} ignores blanks, so the list was non-empty on the way
     * in and empty by the time it reached the entity.
     */
    @Test
    void rejectsABlankCategoryId_ratherThanStoringAnUncategorisedRecipe() {
        ResponseEntity<String> response = restTemplate.postForEntity("/recipes", body("""
                {"title": "Tarte", "categoryIds": [""],
                 "ingredientGroups": [{"ingredients": [{"ingredientId": "%s"}]}],
                 "steps": [{"instruction": "Melanger"}]}
                """.formatted(ingredientId), authorToken), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** And on update, where it would have silently uncategorised a working recipe with a 200. */
    @Test
    void rejectsABlankCategoryIdOnUpdate_ratherThanClearingEveryCategory() {
        String id = createRecipe(minimalRecipeJson(unique("Tarte a garder")), authorToken);

        assertThat(put("/recipes/" + id, "{\"categoryIds\": [\"\"]}", authorToken).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // Still classified, so still reachable from a category filter.
        assertThat(restTemplate.getForObject("/recipes?categoryId=" + categoryId, String.class))
                .contains(id);
    }

    /**
     * A {@code null} element survived validation because Hibernate Validator treats
     * cascading into one as a no-op, and then {@code RecipeService} dereferenced it
     * - a 500 for what is plainly a bad payload.
     */
    @Test
    void rejectsANullElementInsideACollection_asBadRequestAndNotAsAServerError() {
        assertThat(restTemplate.postForEntity("/recipes", body("""
                {"title": "Tarte", "categoryIds": ["%s"],
                 "ingredientGroups": [{"ingredients": [{"ingredientId": "%s"}]}],
                 "steps": [null]}
                """.formatted(categoryId, ingredientId), authorToken), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(restTemplate.postForEntity("/recipes", body("""
                {"title": "Tarte", "categoryIds": ["%s"],
                 "ingredientGroups": [null],
                 "steps": [{"instruction": "Melanger"}]}
                """.formatted(categoryId), authorToken), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(restTemplate.postForEntity("/recipes", body("""
                {"title": "Tarte", "categoryIds": ["%s"], "coverPictures": [null],
                 "ingredientGroups": [{"ingredients": [{"ingredientId": "%s"}]}],
                 "steps": [{"instruction": "Melanger"}]}
                """.formatted(categoryId, ingredientId), authorToken), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * Where the {@code instance} field is verified now that the handlers no longer
     * set it by hand.
     * <p>
     * Spring MVC's {@code HttpEntityMethodProcessor} fills it from the request URI
     * when it is null, which is why {@code ApiExceptionHandler} does not - but that
     * only happens while writing a real response, so a unit test calling a handler
     * directly cannot see it. This can.
     */
    @Test
    void problemResponsesCarryTheRequestUriAsTheirInstance() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/recipes/no-such-recipe-id", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).contains("\"instance\":\"/recipes/no-such-recipe-id\"");
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

    /** Adapts the shared {@code idOf(String)} to this suite, which asserts on whole responses. */
    private String idOf(ResponseEntity<String> response) {
        return ilenreste.unpeu.recettesback.support.HttpTestSupport.idOf(response.getBody());
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
                  "ingredientGroups": [
                    {"ingredients": [{"ingredientId": "%s", "quantity": 200.5}]}
                  ],
                  "steps": [%s]
                }
                """.formatted(categoryId, ingredientId, steps), authorToken);

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
        // The body is otherwise complete, so this reaches reference resolution rather than being
        // turned away by bean validation first - which would prove nothing about the 400 under test.
        ResponseEntity<String> response = restTemplate.postForEntity("/recipes", body("""
                {
                  "title": "Tarte",
                  "categoryIds": ["00000000-0000-0000-0000-000000000000"],
                  "ingredientGroups": [
                    {"ingredients": [{"ingredientId": "%s", "quantity": 200.5}]}
                  ],
                  "steps": [{"instruction": "Melanger"}]
                }
                """.formatted(ingredientId), authorToken), String.class);

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
                {
                  "title": "%s",
                  "categoryIds": ["%s", "%s"],
                  "ingredientGroups": [
                    {"ingredients": [{"ingredientId": "%s", "quantity": 200.5}]}
                  ],
                  "steps": [{"instruction": "Melanger"}]
                }
                """.formatted(unique("Tarte"), categoryId, secondCategoryId, ingredientId), authorToken);

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/recipes?categoryId=" + categoryId, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains(id);
        // In two categories, counted once: the duplicate-row bug the EXISTS query shape avoids.
        assertThat(countOccurrences(response.getBody(), id)).isEqualTo(1);
    }

    /**
     * Also the proof that {@code RecipeSearchRequest} binds at all.
     * <p>
     * It is a record with no no-arg constructor, bound from the query string by name rather than
     * through {@code @RequestParam}. If that binding silently produced an all-null request, every
     * filter would quietly become "no filter" - and a test that only asserts the wanted recipe is
     * <em>present</em> would still pass, because an unfiltered listing contains it too. Hence the
     * negative assertions: they are the half that can actually fail.
     */
    @Test
    void filtersByAuthorAndByTitleFragment() {
        String title = unique("Tarte aux poires");
        String id = createRecipe(minimalRecipeJson(title), authorToken);
        String otherId = createRecipe(minimalRecipeJson(unique("Soupe a l'oignon")), authorToken);

        assertThat(restTemplate.getForObject("/recipes?authorId=" + author.id(), String.class))
                .contains(id);
        assertThat(restTemplate.getForObject("/recipes?title=" + title.toUpperCase(), String.class))
                .contains(id)
                .doesNotContain(otherId);
        assertThat(restTemplate.getForObject("/recipes?authorId=" + otherUser.id(), String.class))
                .doesNotContain(id);
    }

    /**
     * The filters a user can actually type. Only the id filters existed before, which assumed the
     * caller already knew a UUID for the author, category or tag they wanted - true of a dropdown,
     * false of a search box.
     */
    @Test
    void filtersByAuthorCategoryAndTagName_notJustById() {
        String id = createRecipe(minimalRecipeJson(unique("Tarte aux prunes")), authorToken);

        assertThat(restTemplate.getForObject("/recipes?authorName=" + author.username(), String.class))
                .contains(id);
        assertThat(restTemplate.getForObject("/recipes?categoryName=" + categoryName, String.class))
                .contains(id);
        assertThat(restTemplate.getForObject("/recipes?tagName=" + tagName, String.class))
                .contains(id);
        assertThat(restTemplate.getForObject("/recipes?categoryName=" + unique("inexistante"), String.class))
                .doesNotContain(id);
    }

    /**
     * Ordering is chosen from an enum, not from a free-text {@code sort} property appended to a
     * hand-written JPQL projection. An unknown value has to be a 400 naming what is accepted, not
     * the 500 a SemanticException would produce from a typo in a query string.
     */
    @Test
    void ordersByAWhitelistedFieldAndRejectsAnythingElse() {
        String prefix = unique("zzz");
        String first = createRecipe(minimalRecipeJson(prefix + "-a"), authorToken);
        String second = createRecipe(minimalRecipeJson(prefix + "-b"), authorToken);

        String ascending = restTemplate.getForObject(
                "/recipes?title=" + prefix + "&sortBy=TITLE&direction=ASC", String.class);
        assertThat(ascending.indexOf(first)).isLessThan(ascending.indexOf(second));

        String descending = restTemplate.getForObject(
                "/recipes?title=" + prefix + "&sortBy=TITLE&direction=DESC", String.class);
        assertThat(descending.indexOf(second)).isLessThan(descending.indexOf(first));

        assertThat(restTemplate.getForEntity("/recipes?sortBy=bogus", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** The home page: no criteria at all is a valid request, not a 400. */
    @Test
    void listsRecipesWithNoCriteriaAtAll() {
        String id = createRecipe(minimalRecipeJson(unique("Tarte sans filtre")), authorToken);

        ResponseEntity<String> response = restTemplate.getForEntity("/recipes", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains(id);
    }

    /**
     * The introduction round-trips as its own section, and blank text removes it - the only
     * spelling that can, since Jackson cannot tell an explicit null from a missing property.
     */
    @Test
    void carriesAnIntroductionThroughCreateReadAndRemoval() {
        String id = createRecipe("""
                {
                  "title": "%s",
                  "introduction": {"text": "<p>Une tarte de famille.</p>"},
                  "categoryIds": ["%s"],
                  "ingredientGroups": [
                    {"ingredients": [{"ingredientId": "%s", "quantity": 200.5}]}
                  ],
                  "steps": [{"instruction": "Melanger"}]
                }
                """.formatted(unique("Tarte"), categoryId, ingredientId), authorToken);

        assertThat(restTemplate.getForObject("/recipes/" + id, String.class))
                .contains("Une tarte de famille.");

        // Omitting the field leaves it alone.
        put("/recipes/" + id, "{\"title\": \"Tarte revisitee\"}", authorToken);
        assertThat(restTemplate.getForObject("/recipes/" + id, String.class))
                .contains("Une tarte de famille.");

        put("/recipes/" + id, "{\"introduction\": {\"text\": \"\"}}", authorToken);
        assertThat(restTemplate.getForObject("/recipes/" + id, String.class))
                .doesNotContain("Une tarte de famille.");
    }

    /** A recipe nobody can cook is not a half-finished recipe; it is a broken one. */
    @Test
    void rejectsARecipeWithNoStepsOrNoIngredients() {
        assertThat(restTemplate.postForEntity("/recipes", body("""
                {"title": "Tarte", "categoryIds": ["%s"], "ingredientGroups": [],
                 "steps": [{"instruction": "Melanger"}]}
                """.formatted(categoryId), authorToken), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(restTemplate.postForEntity("/recipes", body("""
                {"title": "Tarte", "categoryIds": ["%s"],
                 "ingredientGroups": [{"ingredients": [{"ingredientId": "%s"}]}], "steps": []}
                """.formatted(categoryId, ingredientId), authorToken), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * The hierarchy has to do something when clicked. A recipe filed only under a subcategory is
     * reachable from its parent by default, and only an explicit includeSubcategories=false asks for
     * exact membership - the admin's question, not the browser's.
     * <p>
     * The negative assertions are the half that can fail: the positive ones would also pass if the
     * flag were silently ignored in one direction.
     */
    @Test
    void filtersByAMainCategoryIncludingOrExcludingItsSubcategories() {
        String tartesName = unique("tartes");
        String tartes = idOf(post("/categories",
                "{\"name\":\"%s\",\"parentCategoryId\":\"%s\"}".formatted(tartesName, categoryId),
                adminToken, HttpStatus.CREATED));
        createdSubcategoryIds.add(tartes);

        String tarte = createRecipe("""
                {
                  "title": "%s",
                  "categoryIds": ["%s"],
                  "ingredientGroups": [
                    {"ingredients": [{"ingredientId": "%s", "quantity": 200.5}]}
                  ],
                  "steps": [{"instruction": "Melanger"}]
                }
                """.formatted(unique("Tarte aux prunes"), tartes, ingredientId), authorToken);

        // By id: the default reaches the subcategory, and so does saying so explicitly.
        assertThat(restTemplate.getForObject("/recipes?categoryId=" + categoryId, String.class))
                .contains(tarte);
        assertThat(restTemplate.getForObject(
                "/recipes?categoryId=" + categoryId + "&includeSubcategories=true", String.class))
                .contains(tarte);
        assertThat(restTemplate.getForObject(
                "/recipes?categoryId=" + categoryId + "&includeSubcategories=false", String.class))
                .doesNotContain(tarte);

        // By name, the same two answers.
        assertThat(restTemplate.getForObject("/recipes?categoryName=" + categoryName, String.class))
                .contains(tarte);
        assertThat(restTemplate.getForObject(
                "/recipes?categoryName=" + categoryName + "&includeSubcategories=false", String.class))
                .doesNotContain(tarte);

        // Filtering by the subcategory itself finds it either way.
        assertThat(restTemplate.getForObject(
                "/recipes?categoryId=" + tartes + "&includeSubcategories=false", String.class))
                .contains(tarte);

        // Not a boolean: a binding failure, so 400 rather than a silent default.
        assertThat(restTemplate.getForEntity(
                "/recipes?categoryId=" + categoryId + "&includeSubcategories=perhaps", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * A category name that matches nothing is not "no filter". Answering it with the whole library
     * would look to the user like the filter being ignored.
     */
    @Test
    void aCategoryNameThatMatchesNothingReturnsNoRecipes() {
        createRecipe(minimalRecipeJson(unique("Tarte")), authorToken);

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/recipes?categoryName=" + unique("inexistante"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"totalElements\":0");
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
