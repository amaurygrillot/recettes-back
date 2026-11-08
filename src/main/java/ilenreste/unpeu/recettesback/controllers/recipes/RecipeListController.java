package ilenreste.unpeu.recettesback.controllers.recipes;

import ilenreste.unpeu.recettesback.api.RecipeListsApi;
import ilenreste.unpeu.recettesback.models.recipes.requests.RecipeListRequest;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeListResponse;
import ilenreste.unpeu.recettesback.services.recipes.RecipeListService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * A member's private recipe lists. Every route needs a login, and a list is only ever visible to its owner.
 * <p>
 * The routes, parameters, statuses and security of every method here are declared in {@code openapi.yaml}
 * and reach this class through the generated {@link RecipeListsApi}; see {@code docs/openapi.md}. The
 * content of a list is not served here: it is read through {@code GET /recipes?listId=}, whose guard is
 * {@code RecipeListService.requireVisible}.
 * <p>
 * Nothing here holds a {@code try/catch} or checks a result. {@code RecipeListService} returns a response
 * record or throws, and {@code ApiExceptionHandler} maps each typed exception: 404 for a list that does not
 * exist or is not the caller's (the same answer for both, so an id is never confirmed), 409 for a name
 * already used and for the two limits, told apart by the problem's {@code type}. The service reads the caller
 * from {@code CurrentUserService}, so no method here takes an identity.
 */
@RestController
public class RecipeListController implements RecipeListsApi {

    private final RecipeListService recipeListService;

    public RecipeListController(RecipeListService recipeListService) {
        this.recipeListService = recipeListService;
    }

    @Override
    public ResponseEntity<List<RecipeListResponse>> listRecipeLists(String containing) {
        return ResponseEntity.ok(recipeListService.list(containing));
    }

    @Override
    public ResponseEntity<RecipeListResponse> createRecipeList(RecipeListRequest request) {
        RecipeListResponse created = recipeListService.create(request);
        return ResponseEntity.created(URI.create("/lists/" + created.id())).body(created);
    }

    @Override
    public ResponseEntity<RecipeListResponse> getRecipeList(String id) {
        return ResponseEntity.ok(recipeListService.get(id));
    }

    @Override
    public ResponseEntity<RecipeListResponse> updateRecipeList(String id, RecipeListRequest request) {
        return ResponseEntity.ok(recipeListService.update(id, request));
    }

    @Override
    public ResponseEntity<Void> deleteRecipeList(String id) {
        recipeListService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> addRecipeToList(String id, String recipeId) {
        recipeListService.addRecipe(id, recipeId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> removeRecipeFromList(String id, String recipeId) {
        recipeListService.removeRecipe(id, recipeId);
        return ResponseEntity.noContent().build();
    }
}
