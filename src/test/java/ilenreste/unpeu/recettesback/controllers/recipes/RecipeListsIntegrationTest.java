package ilenreste.unpeu.recettesback.controllers.recipes;

import ilenreste.unpeu.recettesback.exceptions.RecipeListConflict;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipeListsRepository;
import ilenreste.unpeu.recettesback.services.recipes.RecipeListService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static ilenreste.unpeu.recettesback.support.HttpTestSupport.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

/**
 * Recipe lists over real HTTP, through the real filter chain, against the real database: the lifecycle of a list,
 * who may touch it, and every status a list route can answer except the ones about the content of a list, which
 * {@link RecipeListContentIntegrationTest} has.
 * <p>
 * The repository is a spy for one reason: {@link #aNameThatPassesTheCheckButLosesTheInsert} needs the service's
 * name check to say "free" for a name that is taken, which is exactly what losing the race looks like, so that
 * the request reaches the database constraint.
 */
class RecipeListsIntegrationTest extends RecipeListsHttpSupport {

    @MockitoSpyBean
    private RecipeListsRepository spiedLists;

    private static void assertProblem(ResponseEntity<String> response, HttpStatus status) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType())
                .as("an error is problem+json").isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    private static String typeOf(ResponseEntity<String> response) {
        return json(response).path("type").asString("about:blank");
    }

    // ------------------------------------------------------------------------------------------ lifecycle

    @Test
    void creatingAList_is201_withALocation_andTheStoredShape() {
        ResponseEntity<String> created = post("/lists", listJson("  Pour le dimanche  ", PASTA), ownerToken);

        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode list = json(created);
        assertThat(created.getHeaders().getLocation()).hasToString("/lists/" + list.path("id").asString());
        assertThat(list.path("name").asString()).as("trimmed").isEqualTo("Pour le dimanche");
        assertThat(list.path("emoji").asString()).isEqualTo(PASTA);
        assertThat(list.path("recipeCount").asLong()).isZero();
        assertThat(list.path("coverMediaIds").isArray()).isTrue();
        assertThat(list.path("coverMediaIds")).isEmpty();
        assertThat(list.path("createdAt").asString()).isNotBlank();
        assertThat(list.path("updatedAt").asString()).isNotBlank();
        assertThat(list.has("containsRecipe")).as("only GET /lists?containing= sets it").isFalse();
    }

    @Test
    void readingAList_is200_withTheSameShape() {
        String id = createList("Desserts", ownerToken);

        ResponseEntity<String> read = get("/lists/" + id, ownerToken);

        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(read).path("id").asString()).isEqualTo(id);
        assertThat(json(read).path("name").asString()).isEqualTo("Desserts");
        assertThat(json(read).has("containsRecipe")).isFalse();
    }

    @Test
    void renamingAList_is200_andChangesTheNameTheEmojiAndUpdatedAtButNotCreatedAt() throws InterruptedException {
        String id = createList("Desserts", ownerToken);
        JsonNode before = listOf(id, ownerToken);
        Thread.sleep(10);

        ResponseEntity<String> renamed = put("/lists/" + id, listJson("  Gouters  ", PASTA), ownerToken);

        assertThat(renamed.getStatusCode()).as("%s", renamed.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode after = json(renamed);
        assertThat(after.path("name").asString()).isEqualTo("Gouters");
        assertThat(after.path("emoji").asString()).isEqualTo(PASTA);
        assertThat(after.path("createdAt").asString()).isEqualTo(before.path("createdAt").asString());
        assertThat(after.path("updatedAt").asString()).isNotEqualTo(before.path("updatedAt").asString());
        assertThat(listOf(id, ownerToken).path("name").asString()).isEqualTo("Gouters");
    }

    @Test
    void changingOnlyTheEmoji_alsoMovesUpdatedAt() throws InterruptedException {
        String id = createList("Desserts", ownerToken);
        String before = listOf(id, ownerToken).path("updatedAt").asString();
        Thread.sleep(10);

        put("/lists/" + id, listJson("Desserts", PASTA), ownerToken);

        assertThat(listOf(id, ownerToken).path("updatedAt").asString()).isNotEqualTo(before);
    }

    @Test
    void sendingTheValuesTheListAlreadyHas_changesNothing_notEvenUpdatedAt() throws InterruptedException {
        String id = createList("Desserts", ownerToken);
        String before = listOf(id, ownerToken).path("updatedAt").asString();
        Thread.sleep(10);

        ResponseEntity<String> same = put("/lists/" + id, listJson("Desserts", CAKE), ownerToken);

        assertThat(same.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(same).path("updatedAt").asString()).isEqualTo(before);
    }

    @Test
    void addingOrRemovingARecipe_doesNotTouchUpdatedAt() throws InterruptedException {
        String id = createList("Desserts", ownerToken);
        String recipeId = recipe(unique("Tarte"));
        String before = listOf(id, ownerToken).path("updatedAt").asString();
        Thread.sleep(10);

        addToList(id, recipeId, ownerToken);
        assertThat(listOf(id, ownerToken).path("updatedAt").asString()).isEqualTo(before);
        delete("/lists/" + id + "/recipes/" + recipeId, ownerToken);
        assertThat(listOf(id, ownerToken).path("updatedAt").asString()).isEqualTo(before);
    }

    @Test
    void renamingAListToTheSameNameInOtherCase_isAllowed() {
        String id = createList("desserts", ownerToken);

        ResponseEntity<String> fixed = put("/lists/" + id, listJson("Desserts", CAKE), ownerToken);

        assertThat(fixed.getStatusCode()).as("%s", fixed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(fixed).path("name").asString()).isEqualTo("Desserts");
    }

    @Test
    void deletingAList_is204_andTheListIsGone_butItsRecipesAreNot() {
        String id = createList("Desserts", ownerToken);
        String recipeId = recipe(unique("Tarte"));
        addToList(id, recipeId, ownerToken);

        ResponseEntity<String> deleted = delete("/lists/" + id, ownerToken);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertProblem(get("/lists/" + id, ownerToken), HttpStatus.NOT_FOUND);
        assertThat(get("/recipes/" + recipeId, null).getStatusCode()).as("the recipe stays").isEqualTo(HttpStatus.OK);
    }

    @Test
    void deletingARecipeOverHttp_removesItFromTheList_andNotTheListFromTheMember() {
        String id = createList("Desserts", ownerToken);
        String keptId = recipe(unique("Tarte"));
        String deletedId = recipe(unique("Soupe"));
        addToList(id, keptId, ownerToken);
        addToList(id, deletedId, ownerToken);
        assertThat(listOf(id, ownerToken).path("recipeCount").asLong()).isEqualTo(2);

        ResponseEntity<String> deleted = delete("/recipes/" + deletedId, ownerToken);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(listOf(id, ownerToken).path("recipeCount").asLong()).isEqualTo(1);
    }

    @Test
    void theListsOfAMember_comeSortedByNormalisedName_accentsAndCaseIgnored() {
        createList("zeste", ownerToken);
        createList("Épices", ownerToken);
        createList("banane", ownerToken);
        createList("Citron", ownerToken);

        JsonNode lists = json(get("/lists", ownerToken));

        assertThat(lists).extracting(list -> list.path("name").asString())
                .containsExactly("banane", "Citron", "Épices", "zeste");
        assertThat(lists).allSatisfy(list -> assertThat(list.has("containsRecipe")).isFalse());
    }

    @Test
    void aMemberWithNoListGetsAnEmptyArray_not404() {
        ResponseEntity<String> lists = get("/lists", strangerToken);

        assertThat(lists.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(lists).isArray()).isTrue();
        assertThat(json(lists)).isEmpty();
    }

    // ------------------------------------------------------------------------------------------ privacy

    @Test
    void anotherMembersList_isA404_onEveryRoute_andNothingTellsItFromAListThatDoesNotExist() {
        String id = createList("Secret", ownerToken);
        String recipeId = recipe(unique("Tarte"));
        addToList(id, recipeId, ownerToken);
        String nonexistent = "no-such-list";

        for (String token : new String[]{strangerToken, adminToken}) {
            assertNotFoundLikeAnyOther(get("/lists/" + id, token), id, get("/lists/" + nonexistent, token), nonexistent);
            assertNotFoundLikeAnyOther(put("/lists/" + id, listJson("Mine now", CAKE), token), id,
                    put("/lists/" + nonexistent, listJson("Mine now", CAKE), token), nonexistent);
            assertNotFoundLikeAnyOther(delete("/lists/" + id, token), id, delete("/lists/" + nonexistent, token),
                    nonexistent);
            assertNotFoundLikeAnyOther(put("/lists/" + id + "/recipes/" + recipeId, null, token), id,
                    put("/lists/" + nonexistent + "/recipes/" + recipeId, null, token), nonexistent);
            assertNotFoundLikeAnyOther(delete("/lists/" + id + "/recipes/" + recipeId, token), id,
                    delete("/lists/" + nonexistent + "/recipes/" + recipeId, token), nonexistent);
            assertNotFoundLikeAnyOther(get("/recipes?listId=" + id, token), id,
                    get("/recipes?listId=" + nonexistent, token), nonexistent);
        }
        // And none of it changed anything for the owner.
        JsonNode untouched = listOf(id, ownerToken);
        assertThat(untouched.path("name").asString()).isEqualTo("Secret");
        assertThat(untouched.path("recipeCount").asLong()).isEqualTo(1);
    }

    private static void assertNotFoundLikeAnyOther(ResponseEntity<String> notYours, String yoursId,
                                                   ResponseEntity<String> nonexistent, String nonexistentId) {
        assertProblem(notYours, HttpStatus.NOT_FOUND);
        assertProblem(nonexistent, HttpStatus.NOT_FOUND);
        assertThat(json(notYours).path("title").asString()).isEqualTo(json(nonexistent).path("title").asString());
        assertThat(json(notYours).path("detail").asString().replace(yoursId, "ID"))
                .as("the same words, differing only in the id asked about")
                .isEqualTo(json(nonexistent).path("detail").asString().replace(nonexistentId, "ID"));
        assertThat(typeOf(notYours)).isEqualTo(typeOf(nonexistent));
    }

    @Test
    void anAdministratorHasNoBypass_theirListsAreTheirOwnAndTheyDoNotSeeAnyoneElses() {
        String adminsList = createList("Admin shelf", adminToken);
        createList("Owners shelf", ownerToken);

        assertThat(json(get("/lists", adminToken))).extracting(list -> list.path("name").asString())
                .containsExactly("Admin shelf");
        assertProblem(get("/lists/" + adminsList, ownerToken), HttpStatus.NOT_FOUND);
    }

    @Test
    void theListsOfOneMemberNeverShowUpInAnothers() {
        createList("Mine", ownerToken);

        assertThat(json(get("/lists", strangerToken))).isEmpty();
    }

    @Test
    void anotherMembersListIsNotCountedAmongMine_andDoesNotBlockMyNames() {
        createList("Desserts", ownerToken);

        ResponseEntity<String> mine = post("/lists", listJson("Desserts", CAKE), strangerToken);

        assertThat(mine.getStatusCode()).as("two members may both have a list called Desserts")
                .isEqualTo(HttpStatus.CREATED);
    }

    // ------------------------------------------------------------------------------------------ 401

    static Stream<Arguments> everyListRoute() {
        return Stream.of(
                Arguments.of(HttpMethod.GET, "/lists", null),
                Arguments.of(HttpMethod.GET, "/lists?containing=anything", null),
                Arguments.of(HttpMethod.POST, "/lists", "{\"name\":\"x\",\"emoji\":\"🎂\"}"),
                Arguments.of(HttpMethod.GET, "/lists/anything", null),
                Arguments.of(HttpMethod.PUT, "/lists/anything", "{\"name\":\"x\",\"emoji\":\"🎂\"}"),
                Arguments.of(HttpMethod.DELETE, "/lists/anything", null),
                Arguments.of(HttpMethod.PUT, "/lists/anything/recipes/anything", null),
                Arguments.of(HttpMethod.DELETE, "/lists/anything/recipes/anything", null),
                Arguments.of(HttpMethod.GET, "/recipes?listId=anything", null));
    }

    @ParameterizedTest(name = "{0} {1} without a token")
    @MethodSource("everyListRoute")
    void withoutAToken_everyListRoute_is401(HttpMethod method, String path, String payload) {
        ResponseEntity<String> response = restTemplate.exchange(path, method, body(payload, null), String.class);

        assertProblem(response, HttpStatus.UNAUTHORIZED);
    }

    @ParameterizedTest(name = "{0} {1} with a broken token")
    @MethodSource("everyListRoute")
    void withAStaleOrBrokenToken_everyListRoute_is401(HttpMethod method, String path, String payload) {
        // What a token minted before the last restart looks like to the decoder: a signature it cannot verify.
        ResponseEntity<String> response = restTemplate.exchange(path, method,
                body(payload, "Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl"), String.class);

        assertProblem(response, HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theRecipeSearchStaysPublic_forEverythingButAListId() {
        assertThat(get("/recipes", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/recipes?title=tarte", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        // A blank listId is no list at all: the form that sends its untouched fields must not be locked out.
        assertThat(get("/recipes?listId=", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertProblem(get("/recipes?listId=x", null), HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------------------------------ 400

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("a blank name", "{\"name\":\"   \",\"emoji\":\"🎂\"}"),
                Arguments.of("an empty name", "{\"name\":\"\",\"emoji\":\"🎂\"}"),
                Arguments.of("no name", "{\"emoji\":\"🎂\"}"),
                Arguments.of("a null name", "{\"name\":null,\"emoji\":\"🎂\"}"),
                Arguments.of("a name of 101 characters", listJson("x".repeat(101), CAKE)),
                Arguments.of("no emoji", "{\"name\":\"Desserts\"}"),
                Arguments.of("a null emoji", "{\"name\":\"Desserts\",\"emoji\":null}"),
                Arguments.of("an empty emoji", "{\"name\":\"Desserts\",\"emoji\":\"\"}"),
                Arguments.of("a letter for an emoji", "{\"name\":\"Desserts\",\"emoji\":\"a\"}"),
                Arguments.of("a digit for an emoji", "{\"name\":\"Desserts\",\"emoji\":\"1\"}"),
                Arguments.of("an arrow for an emoji", "{\"name\":\"Desserts\",\"emoji\":\"→\"}"),
                Arguments.of("two emojis", listJson("Desserts", CAKE + CAKE)),
                Arguments.of("an emoji and a letter", listJson("Desserts", CAKE + "a")),
                Arguments.of("a text-style heart without its variation selector", listJson("Desserts", "❤")),
                Arguments.of("an emoji sequence over 32 characters",
                        listJson("Desserts", String.join("‍", Collections.nCopies(12, "👩")))),
                Arguments.of("a body that is not JSON", "{"),
                Arguments.of("an empty object", "{}"));
    }

    @ParameterizedTest(name = "POST /lists with {0}")
    @MethodSource("invalidBodies")
    void creatingWithAnInvalidBody_is400(String description, String payload) {
        assertProblem(post("/lists", payload, ownerToken), HttpStatus.BAD_REQUEST);
        assertThat(json(get("/lists", ownerToken))).as("nothing was created").isEmpty();
    }

    @ParameterizedTest(name = "PUT /lists/:id with {0}")
    @MethodSource("invalidBodies")
    void replacingWithAnInvalidBody_is400_andChangesNothing(String description, String payload) {
        String id = createList("Desserts", ownerToken);

        assertProblem(put("/lists/" + id, payload, ownerToken), HttpStatus.BAD_REQUEST);

        assertThat(listOf(id, ownerToken).path("name").asString()).isEqualTo("Desserts");
    }

    @Test
    void aNameOfExactlyOneHundredCharacters_isAccepted() {
        assertThat(post("/lists", listJson("x".repeat(100), CAKE), ownerToken).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    /**
     * NFD turns each of these into three jamo, so the normalised name is 300 characters. If the column were as
     * wide as the name, this would be a 500 for a perfectly valid name.
     */
    @Test
    void aKoreanNameOfTheMaximumLength_isAccepted_evenThoughNormalisingTriplesIt() {
        ResponseEntity<String> created = post("/lists", listJson("한".repeat(100), CAKE), ownerToken);

        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    @ParameterizedTest(name = "an emoji that is {0}")
    @MethodSource("validEmojis")
    void everyKindOfEmoji_isAccepted(String description, String emoji) {
        assertThat(post("/lists", listJson(unique("List"), emoji), ownerToken).getStatusCode())
                .as(description).isEqualTo(HttpStatus.CREATED);
    }

    static Stream<Arguments> validEmojis() {
        return Stream.of(
                Arguments.of("a plain pictograph", "🎂"),
                Arguments.of("a qualified text-default symbol", "❤️"),
                Arguments.of("a ZWJ sequence", "👩‍🍳"),
                Arguments.of("a skin tone", "👍🏽"),
                Arguments.of("a flag", "🇫🇷"),
                Arguments.of("a keycap", "1️⃣"));
    }

    // ------------------------------------------------------------------------------------------ 409

    @Test
    void aNameAlreadyUsed_is409_withItsOwnProblemType() {
        createList("Desserts", ownerToken);

        ResponseEntity<String> duplicate = post("/lists", listJson("Desserts", PASTA), ownerToken);

        assertProblem(duplicate, HttpStatus.CONFLICT);
        assertThat(typeOf(duplicate)).isEqualTo(RecipeListConflict.NAME_TAKEN.type().toString());
        assertThat(json(duplicate).path("title").asString()).isEqualTo(RecipeListConflict.NAME_TAKEN.title());
    }

    @Test
    void aNameAlreadyUsed_isRecognisedWhateverTheAccentsCaseAndSpacing() {
        createList("Désserts de Noël", ownerToken);

        ResponseEntity<String> duplicate = post("/lists", listJson("  desserts   DE noel ", CAKE), ownerToken);

        assertProblem(duplicate, HttpStatus.CONFLICT);
        assertThat(typeOf(duplicate)).isEqualTo(RecipeListConflict.NAME_TAKEN.type().toString());
    }

    @Test
    void renamingAListToAnotherOfMyListsNames_is409_andTheListIsUnchanged() {
        createList("Desserts", ownerToken);
        String gouters = createList("Gouters", ownerToken);

        ResponseEntity<String> clash = put("/lists/" + gouters, listJson("desserts", CAKE), ownerToken);

        assertProblem(clash, HttpStatus.CONFLICT);
        assertThat(typeOf(clash)).isEqualTo(RecipeListConflict.NAME_TAKEN.type().toString());
        assertThat(listOf(gouters, ownerToken).path("name").asString()).isEqualTo("Gouters");
    }

    /**
     * The service checks the name first and the database constraint covers the race. To reach the constraint the
     * check has to say "free" for a name that is taken, which is what losing the race looks like: the row below
     * exists, the check does not see it, and the insert is refused by the unique constraint.
     * <p>
     * The client must get the very answer the check would have given, type included.
     */
    @Test
    void aNameThatPassesTheCheckButLosesTheInsert_isTheSameTypedConflictAsTheCheckGives() {
        createList("Desserts", ownerToken);
        ResponseEntity<String> byTheCheck = post("/lists", listJson("Desserts", CAKE), ownerToken);
        doReturn(Optional.empty()).when(spiedLists).findByOwnerIdAndNormalizedName(owner.id(), "desserts");

        ResponseEntity<String> byTheConstraint = post("/lists", listJson("Desserts", CAKE), ownerToken);

        verify(spiedLists, atLeastOnce()).findByOwnerIdAndNormalizedName(owner.id(), "desserts");
        assertProblem(byTheConstraint, HttpStatus.CONFLICT);
        assertThat(typeOf(byTheConstraint)).isEqualTo(RecipeListConflict.NAME_TAKEN.type().toString());
        assertThat(json(byTheConstraint).path("title").asString()).isEqualTo(json(byTheCheck).path("title").asString());
        assertThat(json(byTheConstraint).path("detail").asString())
                .isEqualTo(json(byTheCheck).path("detail").asString());
        assertThat(json(get("/lists", ownerToken))).as("only the first one exists").hasSize(1);
    }

    @Test
    void theFiftyFirstList_is409_withTheTooManyListsType_andAFreedSlotWorksAgain() {
        String first = null;
        for (int index = 0; index < RecipeListService.MAX_LISTS_PER_MEMBER; index++) {
            String id = createList("Liste " + index, ownerToken);
            first = first == null ? id : first;
        }

        ResponseEntity<String> tooMany = post("/lists", listJson("Une de trop", CAKE), ownerToken);

        assertProblem(tooMany, HttpStatus.CONFLICT);
        assertThat(typeOf(tooMany)).isEqualTo(RecipeListConflict.TOO_MANY_LISTS.type().toString());
        assertThat(json(tooMany).path("title").asString()).isEqualTo(RecipeListConflict.TOO_MANY_LISTS.title());
        assertThat(json(get("/lists", ownerToken))).hasSize(RecipeListService.MAX_LISTS_PER_MEMBER);
        // The limit is per member, and a slot freed by a delete is usable at once.
        assertThat(post("/lists", listJson("Autre membre", CAKE), strangerToken).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(delete("/lists/" + first, ownerToken).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(post("/lists", listJson("Une de plus", CAKE), ownerToken).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void theFiftyFirstListIsRefusedBeforeItsNameIsEvenChecked() {
        IntStream.range(0, RecipeListService.MAX_LISTS_PER_MEMBER).forEach(index -> createList("L" + index, ownerToken));

        ResponseEntity<String> tooMany = post("/lists", listJson("L0", CAKE), ownerToken);

        assertThat(typeOf(tooMany)).as("the limit is the more fundamental refusal")
                .isEqualTo(RecipeListConflict.TOO_MANY_LISTS.type().toString());
    }

    @Test
    void everyOtherErrorKeepsTheGenericProblemType() {
        ResponseEntity<String> notFound = get("/lists/nope", ownerToken);
        ResponseEntity<String> invalid = post("/lists", "{}", ownerToken);

        assertThat(typeOf(notFound)).isEqualTo("about:blank");
        assertThat(typeOf(invalid)).isEqualTo("about:blank");
        assertThat(notFound.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).contains("problem+json");
    }
}
