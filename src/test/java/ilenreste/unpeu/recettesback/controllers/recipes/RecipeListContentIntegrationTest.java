package ilenreste.unpeu.recettesback.controllers.recipes;

import ilenreste.unpeu.recettesback.exceptions.RecipeListConflict;
import ilenreste.unpeu.recettesback.services.recipes.RecipeListService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

/**
 * What is in a list, over real HTTP: adding and removing recipes, the count and the cover mosaic, the
 * {@code containing} question of the « Ajouter à une liste » sheet, the limit of 200, and reading the content
 * through {@code GET /recipes?listId=} with its default order and its {@code ADDED_AT} sort.
 */
class RecipeListContentIntegrationTest extends RecipeListsHttpSupport {

    private static List<String> titlesOf(ResponseEntity<String> page) {
        assertThat(page.getStatusCode()).as("%s", page.getBody()).isEqualTo(HttpStatus.OK);
        List<String> titles = new ArrayList<>();
        json(page).path("content").forEach(recipe -> titles.add(recipe.path("title").asString()));
        return titles;
    }

    // ------------------------------------------------------------------------------------ add and remove

    @Test
    void addingARecipe_is204_andTheListCountsIt() {
        String list = createList("Desserts", ownerToken);
        String recipe = recipe(unique("Tarte"));

        ResponseEntity<String> added = put("/lists/" + list + "/recipes/" + recipe, null, ownerToken);

        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(listOf(list, ownerToken).path("recipeCount").asLong()).isEqualTo(1);
    }

    @Test
    void addingARecipeThatIsAlreadyThere_is204_andChangesNothing_notEvenItsPlaceInTheOrder() {
        String list = createList("Desserts", ownerToken);
        String first = recipe("A first");
        String second = recipe("B second");
        addToList(list, first, ownerToken);
        addToList(list, second, ownerToken);

        ResponseEntity<String> again = put("/lists/" + list + "/recipes/" + first, null, ownerToken);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(listOf(list, ownerToken).path("recipeCount").asLong()).isEqualTo(2);
        // Most recently added first: had the second add moved "A first", it would now lead.
        assertThat(titlesOf(get("/recipes?listId=" + list, ownerToken))).containsExactly("B second", "A first");
    }

    /**
     * A double click, or a client retry: both requests must succeed, not one of them lose a race.
     * <p>
     * The callers are released together by a start gate. Without it the first would usually finish before the last
     * had been scheduled, and a regression to "check, then insert" would pass most of the time.
     */
    @Test
    void identicalAddsInFlightAtTheSameTime_allSucceed() throws Exception {
        String list = createList("Desserts", ownerToken);
        String recipe = recipe(unique("Tarte"));
        int callers = 8;
        CountDownLatch everyoneIsWaiting = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<HttpStatus>> adds = new ArrayList<>();
            for (int caller = 0; caller < callers; caller++) {
                adds.add(pool.submit(() -> {
                    everyoneIsWaiting.countDown();
                    go.await();
                    return HttpStatus.valueOf(put("/lists/" + list + "/recipes/" + recipe, null, ownerToken)
                            .getStatusCode().value());
                }));
            }
            assertThat(everyoneIsWaiting.await(10, TimeUnit.SECONDS)).as("every caller is ready").isTrue();
            go.countDown();

            List<HttpStatus> statuses = new ArrayList<>();
            for (Future<HttpStatus> add : adds) {
                statuses.add(add.get(30, TimeUnit.SECONDS));
            }

            assertThat(statuses).hasSize(callers).containsOnly(HttpStatus.NO_CONTENT);
        } finally {
            pool.shutdownNow();
        }
        assertThat(listOf(list, ownerToken).path("recipeCount").asLong()).isEqualTo(1);
    }

    /**
     * ON CONFLICT DO NOTHING covers the primary key and not the foreign keys. A list (or a recipe) deleted between
     * the checks of the service and its insert is refused by the foreign key; the thing the request names is gone,
     * so that is the 404 a retry would answer, not a 500.
     * <p>
     * To land in that window on purpose, the last check before the insert (does the recipe exist?) is spied: it
     * deletes the row, INSIDE the transaction of the service, and answers what the real check would have answered a
     * moment earlier.
     */
    @Test
    void aListDeletedBetweenTheCheckAndTheInsert_is404_notAServerError() {
        String list = createList("Desserts", ownerToken);
        String recipe = recipe(unique("Tarte"));
        doAnswer(check -> {
            jdbc.update("DELETE FROM recipe_lists WHERE id = ?", list);
            return true;
        }).when(recipesRepository).existsById(recipe);

        ResponseEntity<String> response = put("/lists/" + list + "/recipes/" + recipe, null, ownerToken);

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void aRecipeDeletedBetweenTheCheckAndTheInsert_is404_notAServerError() {
        String list = createList("Desserts", ownerToken);
        String recipe = recipe(unique("Tarte"));
        doAnswer(check -> {
            jdbc.update("DELETE FROM recipes WHERE id = ?", recipe);
            return true;
        }).when(recipesRepository).existsById(recipe);

        ResponseEntity<String> response = put("/lists/" + list + "/recipes/" + recipe, null, ownerToken);

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(listOf(list, ownerToken).path("recipeCount").asLong()).as("nothing half-added").isZero();
    }

    @Test
    void theSameRecipeMayBeInSeveralLists_andInEachContentOnce() {
        String desserts = createList("Desserts", ownerToken);
        String sunday = createList("Dimanche", ownerToken);
        String recipe = recipe("Tarte aux pommes");
        addToList(desserts, recipe, ownerToken);
        addToList(sunday, recipe, ownerToken);

        assertThat(titlesOf(get("/recipes?listId=" + desserts, ownerToken))).containsExactly("Tarte aux pommes");
        assertThat(titlesOf(get("/recipes?listId=" + sunday, ownerToken))).containsExactly("Tarte aux pommes");
    }

    @Test
    void removingARecipe_is204_andRemovingItAgainIsToo() {
        String list = createList("Desserts", ownerToken);
        String recipe = recipe(unique("Tarte"));
        addToList(list, recipe, ownerToken);

        assertThat(delete("/lists/" + list + "/recipes/" + recipe, ownerToken).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(listOf(list, ownerToken).path("recipeCount").asLong()).isZero();
        assertThat(delete("/lists/" + list + "/recipes/" + recipe, ownerToken).getStatusCode())
                .as("a recipe that is not in the list").isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void removingARecipeThatDoesNotExistAtAll_is204_becauseTheListNeverHeldIt() {
        String list = createList("Desserts", ownerToken);

        assertThat(delete("/lists/" + list + "/recipes/no-such-recipe", ownerToken).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void removingARecipeFromOneList_leavesItInTheOthersAndInTheLibrary() {
        String desserts = createList("Desserts", ownerToken);
        String sunday = createList("Dimanche", ownerToken);
        String recipe = recipe(unique("Tarte"));
        addToList(desserts, recipe, ownerToken);
        addToList(sunday, recipe, ownerToken);

        delete("/lists/" + desserts + "/recipes/" + recipe, ownerToken);

        assertThat(listOf(desserts, ownerToken).path("recipeCount").asLong()).isZero();
        assertThat(listOf(sunday, ownerToken).path("recipeCount").asLong()).isEqualTo(1);
        assertThat(get("/recipes/" + recipe, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void addingARecipeThatDoesNotExist_is404_andNamesTheRecipe() {
        String list = createList("Desserts", ownerToken);

        ResponseEntity<String> response = put("/lists/" + list + "/recipes/no-such-recipe", null, ownerToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(response).path("detail").asString()).isEqualTo("No recipe with id no-such-recipe");
    }

    // ------------------------------------------------------------------------------------ the limit of 200

    @Test
    void theTwoHundredAndFirstRecipe_is409_withTheListFullType_butARecipeAlreadyInIsStillFine() {
        String list = createList("Pleine", ownerToken);
        List<String> recipes = IntStream.rangeClosed(1, RecipeListService.MAX_RECIPES_PER_LIST + 1)
                .mapToObj(index -> recipe("Recette " + index)).toList();
        recipes.subList(0, RecipeListService.MAX_RECIPES_PER_LIST).forEach(id -> addToList(list, id, ownerToken));
        String oneTooMany = recipes.getLast();

        ResponseEntity<String> full = put("/lists/" + list + "/recipes/" + oneTooMany, null, ownerToken);

        assertThat(full.getStatusCode()).as("%s", full.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(full).path("type").asString()).isEqualTo(RecipeListConflict.LIST_FULL.type().toString());
        assertThat(json(full).path("title").asString()).isEqualTo(RecipeListConflict.LIST_FULL.title());
        assertThat(listOf(list, ownerToken).path("recipeCount").asLong())
                .isEqualTo(RecipeListService.MAX_RECIPES_PER_LIST);
        // Nothing is being added, so a full list does not refuse it.
        assertThat(put("/lists/" + list + "/recipes/" + recipes.getFirst(), null, ownerToken).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        // And a freed place is usable at once.
        delete("/lists/" + list + "/recipes/" + recipes.getFirst(), ownerToken);
        assertThat(put("/lists/" + list + "/recipes/" + oneTooMany, null, ownerToken).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ------------------------------------------------------------------------------------ containing

    @Test
    void containing_saysForEachListWhetherItHoldsTheRecipe() {
        String holding = createList("Avec", ownerToken);
        String without = createList("Sans", ownerToken);
        String recipe = recipe(unique("Tarte"));
        addToList(holding, recipe, ownerToken);

        ResponseEntity<String> response = get("/lists?containing=" + recipe, ownerToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode lists = json(response);
        assertThat(lists).hasSize(2);
        assertThat(lists.get(0).path("id").asString()).isEqualTo(holding);
        assertThat(lists.get(0).path("containsRecipe").asBoolean()).isTrue();
        assertThat(lists.get(1).path("id").asString()).isEqualTo(without);
        assertThat(lists.get(1).has("containsRecipe")).isTrue();
        assertThat(lists.get(1).path("containsRecipe").asBoolean()).isFalse();
    }

    @Test
    void withoutContaining_thePropertyIsAbsent_notFalse() {
        createList("Desserts", ownerToken);

        assertThat(json(get("/lists", ownerToken)).get(0).has("containsRecipe")).isFalse();
        assertThat(json(get("/lists?containing=", ownerToken)).get(0).has("containsRecipe"))
                .as("a blank containing is no containing").isFalse();
    }

    @Test
    void containingARecipeThatDoesNotExist_is404() {
        createList("Desserts", ownerToken);

        ResponseEntity<String> response = get("/lists?containing=no-such-recipe", ownerToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * Both members file the same recipe. Each must be told about their own list only: with the owner filter missing
     * from the query, each answer would carry both lists.
     */
    @Test
    void containing_onlyEverTellsAboutTheCallersOwnLists() {
        String mine = createList("Mine", ownerToken);
        String theirs = createList("Theirs", strangerToken);
        String recipe = recipe(unique("Tarte"));
        addToList(mine, recipe, ownerToken);
        addToList(theirs, recipe, strangerToken);

        JsonNode owners = json(get("/lists?containing=" + recipe, ownerToken));
        JsonNode strangers = json(get("/lists?containing=" + recipe, strangerToken));

        assertThat(owners).extracting(list -> list.path("id").asString()).containsExactly(mine);
        assertThat(strangers).extracting(list -> list.path("id").asString()).containsExactly(theirs);
        assertThat(owners.get(0).path("containsRecipe").asBoolean()).isTrue();
        assertThat(strangers.get(0).path("containsRecipe").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------------------------ count and mosaic

    @Test
    void theCountIncludesRecipesWithoutAPicture_andTheMosaicIsTheNewestFourThatHaveOne() {
        String list = createList("Dimanche", ownerToken);
        String oldest = media();
        String secondPictureOfNewest = media();
        String second = media();
        String third = media();
        String fourth = media();
        String newest = media();
        // Oldest first. The last recipe has two pictures: only its first one counts. The very newest recipes
        // have none, and take no slot in the mosaic.
        addToList(list, recipe("1", oldest), ownerToken);
        addToList(list, recipe("2", second), ownerToken);
        addToList(list, recipe("3", third), ownerToken);
        addToList(list, recipe("4", fourth), ownerToken);
        addToList(list, recipe("5", newest, secondPictureOfNewest), ownerToken);
        addToList(list, recipe("6 without a picture"), ownerToken);
        addToList(list, recipe("7 without a picture either"), ownerToken);

        JsonNode read = listOf(list, ownerToken);

        assertThat(read.path("recipeCount").asLong()).isEqualTo(7);
        assertThat(read.path("recipeCoverMediaIds")).extracting(JsonNode::asString)
                .containsExactly(newest, fourth, third, second);
        assertThat(json(get("/lists", ownerToken)).get(0).path("recipeCoverMediaIds")).extracting(JsonNode::asString)
                .as("the same on the collection").containsExactly(newest, fourth, third, second);
    }

    @Test
    void aListWhoseRecipesHaveNoPicture_hasAnEmptyMosaic_notAMissingOne() {
        String list = createList("Sans photo", ownerToken);
        addToList(list, recipe(unique("Soupe")), ownerToken);

        JsonNode read = listOf(list, ownerToken);

        assertThat(read.path("recipeCount").asLong()).isEqualTo(1);
        assertThat(read.has("recipeCoverMediaIds")).isTrue();
        assertThat(read.path("recipeCoverMediaIds")).isEmpty();
    }

    // ------------------------------------------------------------------------------------ GET /recipes?listId=

    @Test
    void theContentOfAList_isTheRecipesInIt_mostRecentlyAddedFirstByDefault() {
        String list = createList("Dimanche", ownerToken);
        String inIt = recipe("Zebre");
        String alsoInIt = recipe("Abricot");
        recipe("Not in the list");
        addToList(list, inIt, ownerToken);
        addToList(list, alsoInIt, ownerToken);

        ResponseEntity<String> page = get("/recipes?listId=" + list, ownerToken);

        assertThat(titlesOf(page)).containsExactly("Abricot", "Zebre");
        assertThat(json(page).path("totalElements").asLong()).isEqualTo(2);
        JsonNode card = json(page).path("content").get(0);
        assertThat(card.path("id").asString()).isEqualTo(alsoInIt);
        assertThat(card.has("coverMediaIds")).as("the same cards as the rest of the library").isTrue();
    }

    @Test
    void addedAt_canBeReversed_andOtherSortsStillWorkInAList() {
        String list = createList("Dimanche", ownerToken);
        addToList(list, recipe("Mangue"), ownerToken);
        addToList(list, recipe("Zebre"), ownerToken);
        addToList(list, recipe("Abricot"), ownerToken);

        assertThat(titlesOf(get("/recipes?listId=" + list + "&sortBy=ADDED_AT&direction=ASC", ownerToken)))
                .containsExactly("Mangue", "Zebre", "Abricot");
        assertThat(titlesOf(get("/recipes?listId=" + list + "&direction=ASC", ownerToken)))
                .as("a direction alone reverses the default order")
                .containsExactly("Mangue", "Zebre", "Abricot");
        assertThat(titlesOf(get("/recipes?listId=" + list + "&sortBy=TITLE&direction=ASC", ownerToken)))
                .containsExactly("Abricot", "Mangue", "Zebre");
    }

    @Test
    void theListIsAnAnd_withTheOtherCriteria() {
        String list = createList("Dimanche", ownerToken);
        addToList(list, recipe("Tarte aux pommes"), ownerToken);
        addToList(list, recipe("Soupe"), ownerToken);
        recipe("Tarte aux poires, not in the list");

        assertThat(titlesOf(get("/recipes?listId=" + list + "&title=tarte", ownerToken)))
                .containsExactly("Tarte aux pommes");
    }

    @Test
    void theContentOfAList_isPaged_withACorrectTotal() {
        String list = createList("Dimanche", ownerToken);
        IntStream.rangeClosed(1, 5).forEach(index -> addToList(list, recipe("Recette " + index), ownerToken));

        ResponseEntity<String> lastPage = get("/recipes?listId=" + list + "&page=2&size=2", ownerToken);

        assertThat(titlesOf(lastPage)).containsExactly("Recette 1");
        assertThat(json(lastPage).path("totalElements").asLong()).isEqualTo(5);
        assertThat(json(lastPage).path("totalPages").asLong()).isEqualTo(3);
    }

    @Test
    void anEmptyList_hasAnEmptyContent_notTheWholeLibrary() {
        String list = createList("Vide", ownerToken);
        recipe(unique("Something in the library"));

        ResponseEntity<String> page = get("/recipes?listId=" + list, ownerToken);

        assertThat(titlesOf(page)).isEmpty();
        assertThat(json(page).path("totalElements").asLong()).isZero();
    }

    @Test
    void addedAtWithoutAList_is400() {
        ResponseEntity<String> withoutIt = get("/recipes?sortBy=ADDED_AT", ownerToken);
        ResponseEntity<String> anonymously = get("/recipes?sortBy=ADDED_AT", null);
        ResponseEntity<String> blankList = get("/recipes?sortBy=ADDED_AT&listId=", ownerToken);

        assertThat(withoutIt.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(withoutIt).path("detail").asString()).contains("ADDED_AT", "listId");
        assertThat(anonymously.getStatusCode()).as("a 400 whoever asks").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blankList.getStatusCode()).as("a blank listId is no list").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void withoutAListId_theDefaultOrderIsStillNewestRecipeFirst() {
        String older = recipe(unique("Older"));
        String newer = recipe(unique("Newer"));
        ResponseEntity<String> page = get("/recipes?size=100", null);

        List<String> ids = new ArrayList<>();
        json(page).path("content").forEach(card -> ids.add(card.path("id").asString()));
        assertThat(ids).contains(older, newer);
        assertThat(ids.indexOf(newer)).isLessThan(ids.indexOf(older));
    }
}
