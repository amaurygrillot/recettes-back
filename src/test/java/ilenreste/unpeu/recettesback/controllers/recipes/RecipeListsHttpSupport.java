package ilenreste.unpeu.recettesback.controllers.recipes;

import ilenreste.unpeu.recettesback.entities.recipes.RecipeCoverPictureEntity;
import ilenreste.unpeu.recettesback.properties.MediaProperties;
import ilenreste.unpeu.recettesback.services.media.ImageReencoder;
import ilenreste.unpeu.recettesback.services.media.UploadRateLimiter;
import ilenreste.unpeu.recettesback.support.TestImages;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // This context is not the one the other suites share (it carries the spies below), and every distinct context
        // the suite creates stays alive for the whole run with a pool of its own, on a development PostgreSQL that
        // allows 100 connections in all. Ten idle connections per context is how a full build runs out of them
        // ("too many clients already") on a machine where the application and an IDE are connected too. Eight is the
        // most this suite needs at once, for the test that sends eight identical requests together.
        properties = {"spring.datasource.hikari.maximum-pool-size=8",
                // Lists' photos write real files. Under target/, as MediaUploadIntegrationTest does, never ./media.
                "app.media.storage-path=target/test-media-recipe-lists"})
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
    /**
     * Spied, here and not in the suites, so that both suites run in ONE application context: a spy changes the
     * context, and two suites with different spies would be two contexts and two connection pools. Tests that stub a
     * method do so to make a race happen on purpose; the rest see the real repository.
     */
    @MockitoSpyBean
    protected RecipesRepository recipesRepository;
    @MockitoSpyBean
    protected RecipeListsRepository listsRepository;
    /**
     * Spied, here, for the same reason as the repositories above (one shared context): the tests that need a 429 or a
     * 503 over real HTTP make these two throw, instead of spending twenty uploads or saturating the decode pool.
     */
    @MockitoSpyBean
    protected UploadRateLimiter rateLimiter;
    @MockitoSpyBean
    protected ImageReencoder reencoder;
    @Autowired
    protected MediaProperties mediaProperties;
    @Autowired
    protected MediaRepository mediaRepository;
    @Autowired
    protected RecipeListEntriesRepository entriesRepository;
    @Autowired
    protected JdbcTemplate jdbc;
    /** The transaction-bound one: used from inside a spied call, on the thread of the request. */
    @PersistenceContext
    protected EntityManager entityManager;
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
            // A list's photo is a media row the list points at: read where its files are before the list goes, delete
            // the row after it (the list references it), then the files. The service does exactly this on a delete.
            List<Map<String, Object>> photos = coverRowsOf(accounts);
            accounts.forEach(account -> listsRepository.deleteAll(
                    listsRepository.findByOwnerIdOrderByNormalizedNameAscNameAsc(account.id())));
            recipesRepository.deleteAllById(recipeIds);
            mediaRepository.deleteAllById(mediaIds);
            photos.forEach(photo -> {
                jdbc.update("DELETE FROM media WHERE id = ?", photo.get("id"));
                deleteStoredFile((String) photo.get("storage_key"));
                deleteStoredFile((String) photo.get("thumbnail_storage_key"));
            });
        } finally {
            accounts.reversed().forEach(TestAccount::close);
        }
    }

    private List<Map<String, Object>> coverRowsOf(List<TestAccount> accounts) {
        return accounts.stream()
                .flatMap(account -> jdbc.queryForList("""
                        SELECT m.id, m.storage_key, m.thumbnail_storage_key FROM media m
                        JOIN recipe_lists l ON l.cover_media_id = m.id WHERE l.owner_id = ?
                        """, account.id()).stream())
                .toList();
    }

    private void deleteStoredFile(String storageKey) {
        try {
            Files.deleteIfExists(storageRoot().resolve(storageKey));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    protected Path storageRoot() {
        return mediaProperties.storagePath().toAbsolutePath().normalize();
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

    /** {@code multipart/form-data} with one part named {@code file}, sent with the given verb. */
    protected ResponseEntity<String> sendFile(HttpMethod method, String path, byte[] content, String token) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return "photo.jpg";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (token != null) {
            headers.set(HttpHeaders.AUTHORIZATION, token);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    /** {@code PUT /lists/{id}/cover} with a small JPEG. */
    protected ResponseEntity<String> putCover(String listId, String token) {
        return sendFile(HttpMethod.PUT, "/lists/" + listId + "/cover", TestImages.jpeg(300, 200), token);
    }

    /** {@code GET} of any binary path, with optional extra headers (a conditional request, say). */
    protected ResponseEntity<byte[]> getBytes(String path, String token, String... headerNameAndValue) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set(HttpHeaders.AUTHORIZATION, token);
        }
        for (int index = 0; index < headerNameAndValue.length; index += 2) {
            headers.set(headerNameAndValue[index], headerNameAndValue[index + 1]);
        }
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }

    /** Every file under the storage root, as a key relative to it. In-flight temporary files are not counted. */
    protected Set<String> storedFiles() {
        Path root = storageRoot();
        if (!Files.isDirectory(root)) {
            return Set.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> !file.getFileName().toString().startsWith(".upload-"))
                    .map(file -> root.relativize(file).toString().replace('\\', '/'))
                    .collect(Collectors.toSet());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** The row and both files of a photo, read from the database by its id (its {@code coverVersion}). */
    protected Optional<Map<String, Object>> mediaRow(String mediaId) {
        return jdbc.queryForList("SELECT id, storage_key, thumbnail_storage_key FROM media WHERE id = ?", mediaId)
                .stream().findFirst();
    }

    protected boolean fileExists(String storageKey) {
        return Files.isRegularFile(storageRoot().resolve(storageKey));
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
