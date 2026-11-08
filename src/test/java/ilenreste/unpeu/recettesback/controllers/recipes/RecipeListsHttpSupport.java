package ilenreste.unpeu.recettesback.controllers.recipes;

import ilenreste.unpeu.recettesback.entities.recipes.RecipeCoverPictureEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeEntity;
import ilenreste.unpeu.recettesback.repositories.media.MediaRepository;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipeListEntriesRepository;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipeListsRepository;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipesRepository;
import ilenreste.unpeu.recettesback.repositories.users.RolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UserRolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
import ilenreste.unpeu.recettesback.support.RecipeListFixtures;
import ilenreste.unpeu.recettesback.support.TestAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import static ilenreste.unpeu.recettesback.support.HttpTestSupport.body;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the two recipe list suites share: three real accounts (two members and an administrator), their tokens,
 * the HTTP verbs with a token attached, and recipes and media created straight in the database.
 * <p>
 * The tests drive the real filter chain over a real port, so there is no transaction to roll back: every row a
 * test creates is deleted in {@link #tearDown()}, in an order the foreign keys accept — lists first (they
 * point at their owners), then recipes (which take their entries with them), then media, then accounts.
 * <p>
 * Recipes are inserted directly rather than through {@code POST /recipes}: what is under test is the lists,
 * and a recipe over HTTP needs a category, an ingredient and a step before it exists.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
abstract class RecipeListsHttpSupport {

    /** 🎂 and 🍝, spelled with escapes so a source encoding can never change what is sent. */
    protected static final String CAKE = "\uD83C\uDF82";
    protected static final String PASTA = "\uD83C\uDF5D";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    protected TestRestTemplate restTemplate;
    @Autowired
    protected UsersRepository usersRepository;
    @Autowired
    protected RecipesRepository recipesRepository;
    @Autowired
    protected RecipeListsRepository listsRepository;
    @Autowired
    protected MediaRepository mediaRepository;
    @Autowired
    protected RecipeListEntriesRepository entriesRepository;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    private RolesRepository rolesRepository;
    @Autowired
    private UserRolesRepository userRolesRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    protected TestAccount owner;
    protected TestAccount stranger;
    protected TestAccount admin;
    protected String ownerToken;
    protected String strangerToken;
    protected String adminToken;

    private final List<String> recipeIds = new ArrayList<>();
    private final List<String> mediaIds = new ArrayList<>();

    @BeforeEach
    void setUpAccounts() {
        owner = TestAccount.create(usersRepository, rolesRepository, userRolesRepository, passwordEncoder, "USER");
        stranger = TestAccount.create(usersRepository, rolesRepository, userRolesRepository, passwordEncoder, "USER");
        admin = TestAccount.create(usersRepository, rolesRepository, userRolesRepository, passwordEncoder,
                "USER", "ADMIN");
        ownerToken = owner.bearerToken(restTemplate);
        strangerToken = stranger.bearerToken(restTemplate);
        adminToken = admin.bearerToken(restTemplate);
    }

    /**
     * Cleans up whatever {@link #setUpAccounts()} got as far as creating, and keeps going when one step fails: these
     * rows are committed to the development database, so a half-finished teardown is a leak in the library the
     * developer actually uses.
     */
    @AfterEach
    void tearDown() {
        List<TestAccount> accounts = Stream.of(owner, stranger, admin).filter(Objects::nonNull).toList();
        try {
            accounts.forEach(account -> listsRepository.deleteAll(
                    listsRepository.findByOwnerIdOrderByNormalizedNameAscNameAsc(account.id())));
            recipesRepository.deleteAllById(recipeIds);
            mediaRepository.deleteAllById(mediaIds);
        } finally {
            accounts.reversed().forEach(TestAccount::close);
        }
    }

    // ------------------------------------------------------------------------------------------ HTTP verbs

    protected ResponseEntity<String> get(String path, String token) {
        return restTemplate.exchange(path, HttpMethod.GET, body(null, token), String.class);
    }

    protected ResponseEntity<String> post(String path, String json, String token) {
        return restTemplate.exchange(path, HttpMethod.POST, body(json, token), String.class);
    }

    protected ResponseEntity<String> put(String path, String json, String token) {
        return restTemplate.exchange(path, HttpMethod.PUT, body(json, token), String.class);
    }

    protected ResponseEntity<String> delete(String path, String token) {
        return restTemplate.exchange(path, HttpMethod.DELETE, body(null, token), String.class);
    }

    protected static JsonNode json(ResponseEntity<String> response) {
        return JSON.readTree(response.getBody());
    }

    /** A request body for a list, escaped properly whatever the name holds. */
    protected static String listJson(String name, String emoji) {
        return JSON.writeValueAsString(Map.of("name", name, "emoji", emoji));
    }

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ------------------------------------------------------------------------------------------ lists

    /** Creates a list over HTTP, asserting the 201, and returns its id. */
    protected String createList(String name, String token) {
        ResponseEntity<String> created = post("/lists", listJson(name, CAKE), token);
        assertThat(created.getStatusCode()).as("POST /lists %s -> %s", name, created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return json(created).path("id").asString();
    }

    protected void addToList(String listId, String recipeId, String token) {
        ResponseEntity<String> added = put("/lists/" + listId + "/recipes/" + recipeId, null, token);
        assertThat(added.getStatusCode()).as("PUT recipe -> %s", added.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    protected JsonNode listOf(String listId, String token) {
        ResponseEntity<String> read = get("/lists/" + listId, token);
        assertThat(read.getStatusCode()).as("GET /lists/%s -> %s", listId, read.getBody()).isEqualTo(HttpStatus.OK);
        return json(read);
    }

    // ------------------------------------------------------------------------------------------ recipes

    /** A recipe written by {@link #owner}, with these media as its cover pictures in this order. */
    protected String recipe(String title, String... coverMediaIds) {
        RecipeEntity recipe = new RecipeEntity();
        recipe.setTitle(title);
        recipe.setAuthor(usersRepository.getReferenceById(owner.id()));
        for (int position = 0; position < coverMediaIds.length; position++) {
            RecipeCoverPictureEntity cover = new RecipeCoverPictureEntity();
            cover.setRecipe(recipe);
            cover.setMedia(mediaRepository.getReferenceById(coverMediaIds[position]));
            cover.setPosition(position);
            recipe.getCoverPictures().add(cover);
        }
        String id = recipesRepository.saveAndFlush(recipe).getId();
        recipeIds.add(id);
        return id;
    }

    protected String media() {
        String id = fixtures().media().getId();
        mediaIds.add(id);
        return id;
    }

    private RecipeListFixtures fixtures() {
        return new RecipeListFixtures(usersRepository, recipesRepository, mediaRepository, listsRepository,
                entriesRepository);
    }
}
