package ilenreste.unpeu.recettesback.controllers.reference;

import ilenreste.unpeu.recettesback.entities.recipes.RecipeEntity;
import ilenreste.unpeu.recettesback.entities.reference.CategoryEntity;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipesRepository;
import ilenreste.unpeu.recettesback.repositories.reference.CategoriesRepository;
import ilenreste.unpeu.recettesback.repositories.reference.CategoryRelationsRepository;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static ilenreste.unpeu.recettesback.support.HttpTestSupport.body;
import static ilenreste.unpeu.recettesback.support.HttpTestSupport.idOf;
import static ilenreste.unpeu.recettesback.support.HttpTestSupport.unique;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reference endpoints against the real security chain. Two things here cannot be tested any
 * other way: the permission asymmetry between creating and editing an ingredient, and the refusal
 * to delete a row a recipe still points at.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ReferenceEndpointsIntegrationTest {

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
    private RecipesRepository recipesRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private TestAccount admin;
    private TestAccount user;
    private String adminToken;
    private String userToken;
    private final List<String> createdCategoryIds = new ArrayList<>();
    private final List<String> createdRecipeIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // Admins need USER too, or the hasRole("USER") URL rules lock them out.
        admin = TestAccount.create(usersRepository, rolesRepository, userRolesRepository,
                passwordEncoder, "USER", "ADMIN");
        user = TestAccount.create(usersRepository, rolesRepository, userRolesRepository,
                passwordEncoder, "USER");
        adminToken = admin.bearerToken(restTemplate);
        userToken = user.bearerToken(restTemplate);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        createdRecipeIds.forEach(recipesRepository::deleteById);
        // The relation row first: every category has one, a main category's points at itself, so
        // the foreign key outlives the category unless it is cleared by hand.
        createdCategoryIds.forEach(id -> {
            categoryRelationsRepository.deleteById(id);
            categoriesRepository.deleteById(id);
        });
        user.close();
        admin.close();
    }

    private String createCategoryAsAdmin(String name) {
        return createCategoryAsAdmin(name, null);
    }

    private String createCategoryAsAdmin(String name, String parentCategoryId) {
        String json = parentCategoryId == null
                ? "{\"name\":\"%s\"}".formatted(name)
                : "{\"name\":\"%s\",\"parentCategoryId\":\"%s\"}".formatted(name, parentCategoryId);
        ResponseEntity<String> response = restTemplate.postForEntity("/categories",
                body(json, adminToken), String.class);
        assertThat(response.getStatusCode())
                .as("POST /categories -> %s", response.getBody()).isEqualTo(HttpStatus.CREATED);

        String id = idOf(response.getBody());
        // Children first on the way out, or the delete trips the relation foreign key.
        createdCategoryIds.addFirst(id);
        return id;
    }

    @Test
    void readsArePublic_butWritesNeedAnAdmin() {
        assertThat(restTemplate.getForEntity("/categories", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // A plain USER is authenticated but not permitted: 403, not 401.
        assertThat(restTemplate.postForEntity("/categories",
                body("{\"name\":\"" + unique("dessert") + "\"}", userToken), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // No credential offered at all: 401, not 403.
        assertThat(restTemplate.postForEntity("/categories",
                body("{\"name\":\"" + unique("dessert") + "\"}", null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        createCategoryAsAdmin(unique("dessert"));
    }

    @Test
    void refusesADuplicateAndSaysWhichRowItCollidedWith() {
        String name = unique("dessert");
        String id = createCategoryAsAdmin(name);

        // Different spelling, same normalized name - which is the whole reason that column exists.
        ResponseEntity<String> response = restTemplate.postForEntity("/categories",
                body("{\"name\":\"%s\"}".formatted(name.toUpperCase()), adminToken), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains(id);
    }

    @Test
    void rejectsABlankName() {
        ResponseEntity<String> response = restTemplate.postForEntity("/categories",
                body("{\"name\":\"  \"}", adminToken), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void renamesAndThenDeletesAnUnusedCategory() {
        String id = createCategoryAsAdmin(unique("dessert"));
        String newName = unique("desserts-et-gouters");

        ResponseEntity<String> renamed = restTemplate.exchange("/categories/" + id, HttpMethod.PUT,
                body("{\"name\":\"%s\"}".formatted(newName), adminToken), String.class);
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(renamed.getBody()).contains(newName);

        ResponseEntity<String> deleted = restTemplate.exchange("/categories/" + id, HttpMethod.DELETE,
                body(null, adminToken), String.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        createdCategoryIds.remove(id);

        assertThat(restTemplate.getForEntity("/categories/" + id, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void refusesToDeleteACategoryARecipeStillPointsAt() {
        String categoryId = createCategoryAsAdmin(unique("dessert"));
        attachRecipeTo(categoryId);

        ResponseEntity<String> response = restTemplate.exchange("/categories/" + categoryId,
                HttpMethod.DELETE, body(null, adminToken), String.class);

        // 409 and never a cascade: an admin tidying the category list must not silently gut
        // other people's recipes.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(categoriesRepository.findById(categoryId)).isPresent();
    }

    /**
     * Creates a recipe through the repository rather than the API, because the recipe endpoints are
     * not what this test is about. RecipeEntity.author is @CreatedBy, so the security context has
     * to carry someone for the insert to satisfy author_id NOT NULL.
     */
    private void attachRecipeTo(String categoryId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("userId", admin.id())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));

        CategoryEntity category = categoriesRepository.findById(categoryId).orElseThrow();
        RecipeEntity recipe = new RecipeEntity();
        recipe.setTitle("Tarte aux pommes");
        recipe.getCategories().add(category);
        createdRecipeIds.add(recipesRepository.saveAndFlush(recipe).getId());
    }

    @Test
    void placesACategoryUnderAnotherOneAndReadsTheTreeBackBothWays() {
        String desserts = createCategoryAsAdmin(unique("desserts"));
        String tartes = createCategoryAsAdmin(unique("tartes"), desserts);
        String tartesAuxPommes = createCategoryAsAdmin(unique("tartes-aux-pommes"), tartes);

        // A root reports MAIN and no parent - never the self-reference the schema actually holds.
        assertThat(restTemplate.getForObject("/categories/" + desserts, String.class))
                .contains("\"categoryType\":\"MAIN\"")
                .contains("\"parentCategoryId\":null");
        assertThat(restTemplate.getForObject("/categories/" + tartes, String.class))
                .contains("\"categoryType\":\"SUB\"")
                .contains(desserts);

        // Everything below, at any depth - not just the direct children.
        assertThat(restTemplate.getForObject("/categories/" + desserts + "/subcategories", String.class))
                .contains(tartes)
                .contains(tartesAuxPommes);

        // The ancestor chain, nearest first: breadcrumb order reversed.
        String parents = restTemplate.getForObject(
                "/categories/" + tartesAuxPommes + "/parent-categories", String.class);
        assertThat(parents.indexOf(tartes)).isLessThan(parents.indexOf(desserts));
        assertThat(restTemplate.getForObject(
                "/categories/" + desserts + "/parent-categories", String.class)).isEqualTo("[]");

        // Nested, so a navigation menu is one call.
        assertThat(restTemplate.getForObject("/categories/hierarchy", String.class))
                .contains(desserts)
                .contains(tartes);
    }

    /** Reads stay public all the way down the hierarchy routes; writes stay admin-only. */
    @Test
    void hierarchyReadsArePublic() {
        String desserts = createCategoryAsAdmin(unique("desserts"));

        assertThat(restTemplate.getForEntity("/categories/hierarchy", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/categories/" + desserts + "/subcategories", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/categories/" + desserts + "/parent-categories", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * "This category has nothing under it" and "there is no such category" are different answers,
     * and an empty list for the second would hide a broken link on the client.
     */
    @Test
    void hierarchyReadsAnswer404ForAnUnknownCategory() {
        assertThat(restTemplate.getForEntity("/categories/no-such-id/subcategories", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.getForEntity("/categories/no-such-id/parent-categories", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsAParentCategoryThatDoesNotExist() {
        // 400 and not 404: /categories is perfectly reachable, it is the payload that is wrong.
        ResponseEntity<String> response = restTemplate.postForEntity("/categories",
                body("{\"name\":\"%s\",\"parentCategoryId\":\"00000000-0000-0000-0000-000000000000\"}"
                        .formatted(unique("tartes")), adminToken), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("parentCategoryId");
    }

    @Test
    void refusesToCloseACycleInTheHierarchy() {
        // A cycle makes every walk below non-terminating - a hang, not an error.
        String desserts = createCategoryAsAdmin(unique("desserts"));
        String tartes = createCategoryAsAdmin(unique("tartes"), desserts);

        ResponseEntity<String> response = restTemplate.exchange("/categories/" + desserts,
                HttpMethod.PUT,
                body("{\"name\":\"desserts-%s\",\"parentCategoryId\":\"%s\"}"
                        .formatted(UUID.randomUUID().toString().substring(0, 8), tartes), adminToken),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void refusesToDeleteACategoryThatStillHasSubcategories() {
        String desserts = createCategoryAsAdmin(unique("desserts"));
        createCategoryAsAdmin(unique("tartes"), desserts);

        ResponseEntity<String> response = restTemplate.exchange("/categories/" + desserts,
                HttpMethod.DELETE, body(null, adminToken), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("subcategories");
    }

    /** An update is a full replacement, so dropping the field promotes the category to a root. */
    @Test
    void promotesASubcategoryToARootWhenTheParentIsOmitted() {
        String desserts = createCategoryAsAdmin(unique("desserts"));
        String tartes = createCategoryAsAdmin(unique("tartes"), desserts);

        ResponseEntity<String> response = restTemplate.exchange("/categories/" + tartes, HttpMethod.PUT,
                body("{\"name\":\"%s\"}".formatted(unique("tartes")), adminToken), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"categoryType\":\"MAIN\"");
        assertThat(restTemplate.getForObject("/categories/" + desserts + "/subcategories", String.class))
                .isEqualTo("[]");
    }

    /**
     * The asymmetry the design calls out: a recipe cannot be written without the ingredients it
     * uses, so adding one is open to everybody - but renaming a shared row silently rewrites every
     * recipe referencing it, so that stays with admins.
     */
    @Test
    void anyAuthenticatedUserMayAddAnIngredient_butOnlyAnAdminMayEditOne() {
        ResponseEntity<String> created = restTemplate.postForEntity("/ingredients",
                body("{\"name\":\"%s\"}".formatted(unique("farine")), userToken), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = idOf(created.getBody());

        try {
            ResponseEntity<String> edited = restTemplate.exchange("/ingredients/" + id, HttpMethod.PUT,
                    body("{\"name\":\"%s\"}".formatted(unique("farine-t55")), userToken), String.class);
            assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            ResponseEntity<String> deleted = restTemplate.exchange("/ingredients/" + id, HttpMethod.DELETE,
                    body(null, userToken), String.class);
            assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        } finally {
            restTemplate.exchange("/ingredients/" + id, HttpMethod.DELETE, body(null, adminToken), String.class);
        }
    }

    @Test
    void ingredientSearchMatchesTheNormalizedName() {
        String name = "Crème" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<String> created = restTemplate.postForEntity("/ingredients",
                body("{\"name\":\"%s\"}".formatted(name), userToken), String.class);
        String id = idOf(created.getBody());

        try {
            // Typed without the accent, and still finds it - which is what the normalized column is for.
            ResponseEntity<String> found = restTemplate.getForEntity(
                    "/ingredients?namePrefix=creme", String.class);

            assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(found.getBody()).contains(id);

            // A prefix, not a substring: a trailing wildcard can use the index on normalized_name
            // and a leading one cannot. Also the half that proves the filter is applied at all -
            // an unfiltered listing would contain the row either way.
            assertThat(restTemplate.getForObject("/ingredients?namePrefix=eme", String.class))
                    .doesNotContain(id);
        } finally {
            restTemplate.exchange("/ingredients/" + id, HttpMethod.DELETE, body(null, adminToken), String.class);
        }
    }

    /**
     * Tags and units go through the same AbstractReferenceService as categories, so this walks
     * their lifecycle end to end rather than repeating the rule-by-rule unit tests: what is worth
     * proving here is that each concrete service is wired to its own repository and its own
     * usage check, which a mocked test cannot show.
     */
    @Test
    void tagsFollowTheSameLifecycleAsCategories() {
        String name = unique("vegetarien");
        ResponseEntity<String> created = restTemplate.postForEntity("/tags",
                body("{\"name\":\"%s\"}".formatted(name), adminToken), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = idOf(created.getBody());

        assertThat(restTemplate.getForEntity("/tags/" + id, String.class).getBody()).contains(name);

        // Same normalized name, different spelling: still a conflict.
        assertThat(restTemplate.postForEntity("/tags",
                body("{\"name\":\"%s\"}".formatted(name.toUpperCase()), adminToken), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        String renamed = unique("vegetarien-strict");
        assertThat(restTemplate.exchange("/tags/" + id, HttpMethod.PUT,
                body("{\"name\":\"%s\"}".formatted(renamed), adminToken), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(restTemplate.exchange("/tags/" + id, HttpMethod.DELETE,
                body(null, adminToken), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(restTemplate.getForEntity("/tags/" + id, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unitsCarryAnAbbreviationThroughCreateAndUpdate() {
        String name = unique("decilitre");
        ResponseEntity<String> created = restTemplate.postForEntity("/units",
                body("{\"name\":\"%s\",\"abbreviation\":\"dl\"}".formatted(name), adminToken),
                String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).contains("\"abbreviation\":\"dl\"");
        String id = idOf(created.getBody());

        try {
            ResponseEntity<String> updated = restTemplate.exchange("/units/" + id, HttpMethod.PUT,
                    body("{\"name\":\"%s\",\"abbreviation\":\"dL\"}".formatted(name), adminToken),
                    String.class);
            assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(updated.getBody()).contains("\"abbreviation\":\"dL\"");

            assertThat(restTemplate.getForEntity("/units/" + id, String.class).getBody())
                    .contains("\"abbreviation\":\"dL\"");
        } finally {
            restTemplate.exchange("/units/" + id, HttpMethod.DELETE, body(null, adminToken), String.class);
        }
    }

    @Test
    void unitsAreSeededSoARecipeCanBeWrittenOnDayOne() {
        // The seeder runs at startup; without it POST /recipes is unusable until someone inserts
        // rows by hand, which is the trap this project already had for the USER role.
        ResponseEntity<String> response = restTemplate.getForEntity("/units", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("gramme").contains("pincée");
    }
}
