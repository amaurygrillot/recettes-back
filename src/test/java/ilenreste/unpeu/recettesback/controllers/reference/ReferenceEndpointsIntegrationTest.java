package ilenreste.unpeu.recettesback.controllers.reference;

import ilenreste.unpeu.recettesback.entities.recipes.RecipeEntity;
import ilenreste.unpeu.recettesback.entities.reference.CategoryEntity;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipesRepository;
import ilenreste.unpeu.recettesback.repositories.reference.CategoriesRepository;
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
        createdCategoryIds.forEach(categoriesRepository::deleteById);
        user.close();
        admin.close();
    }

    private HttpEntity<String> body(String json, String authorization) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (authorization != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        return new HttpEntity<>(json, headers);
    }

    private String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String idOf(String json) {
        int start = json.indexOf("\"id\":\"") + 6;
        return json.substring(start, json.indexOf('"', start));
    }

    private String createCategoryAsAdmin(String name) {
        ResponseEntity<String> response = restTemplate.postForEntity("/categories",
                body("{\"name\":\"%s\"}".formatted(name), adminToken), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String id = idOf(response.getBody());
        createdCategoryIds.add(id);
        return id;
    }

    @Test
    void readsArePublic_butWritesNeedAnAdmin() {
        assertThat(restTemplate.getForEntity("/categories", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // A plain USER is authenticated but not permitted: 403, not 401.
        assertThat(restTemplate.postForEntity("/categories",
                body("{\"name\":\"" + uniqueName("dessert") + "\"}", userToken), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // No credential offered at all: 401, not 403.
        assertThat(restTemplate.postForEntity("/categories",
                body("{\"name\":\"" + uniqueName("dessert") + "\"}", null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        createCategoryAsAdmin(uniqueName("dessert"));
    }

    @Test
    void refusesADuplicateAndSaysWhichRowItCollidedWith() {
        String name = uniqueName("dessert");
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
        String id = createCategoryAsAdmin(uniqueName("dessert"));
        String newName = uniqueName("desserts-et-gouters");

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
        String categoryId = createCategoryAsAdmin(uniqueName("dessert"));
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

    /**
     * The asymmetry the design calls out: a recipe cannot be written without the ingredients it
     * uses, so adding one is open to everybody - but renaming a shared row silently rewrites every
     * recipe referencing it, so that stays with admins.
     */
    @Test
    void anyAuthenticatedUserMayAddAnIngredient_butOnlyAnAdminMayEditOne() {
        ResponseEntity<String> created = restTemplate.postForEntity("/ingredients",
                body("{\"name\":\"%s\"}".formatted(uniqueName("farine")), userToken), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = idOf(created.getBody());

        try {
            ResponseEntity<String> edited = restTemplate.exchange("/ingredients/" + id, HttpMethod.PUT,
                    body("{\"name\":\"%s\"}".formatted(uniqueName("farine-t55")), userToken), String.class);
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
            ResponseEntity<String> found = restTemplate.getForEntity("/ingredients?q=creme", String.class);

            assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(found.getBody()).contains(id);
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
        String name = uniqueName("vegetarien");
        ResponseEntity<String> created = restTemplate.postForEntity("/tags",
                body("{\"name\":\"%s\"}".formatted(name), adminToken), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = idOf(created.getBody());

        assertThat(restTemplate.getForEntity("/tags/" + id, String.class).getBody()).contains(name);

        // Same normalized name, different spelling: still a conflict.
        assertThat(restTemplate.postForEntity("/tags",
                body("{\"name\":\"%s\"}".formatted(name.toUpperCase()), adminToken), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        String renamed = uniqueName("vegetarien-strict");
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
        String name = uniqueName("decilitre");
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
