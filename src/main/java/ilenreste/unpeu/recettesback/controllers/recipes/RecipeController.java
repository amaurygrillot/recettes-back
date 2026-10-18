package ilenreste.unpeu.recettesback.controllers.recipes;

import ilenreste.unpeu.recettesback.api.RecipesApi;
import ilenreste.unpeu.recettesback.models.recipes.requests.CreateRecipeRequest;
import ilenreste.unpeu.recettesback.models.recipes.requests.RecipeSearchRequest;
import ilenreste.unpeu.recettesback.models.recipes.requests.UpdateRecipeRequest;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeResponse;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeSummaryResponse;
import ilenreste.unpeu.recettesback.services.recipes.RecipeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import lombok.extern.log4j.Log4j2;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Recipes. Reads are public; writing needs a login, and editing or deleting
 * needs to be the author or an admin.
 * <p>
 * Everything but the search is declared in {@code openapi.yaml} and reaches this
 * class through the generated {@link RecipesApi}; see {@code docs/openapi.md}.
 * The search is the one signature written by hand, for the reason on
 * {@link #searchRecipes}.
 * <p>
 * The author-or-admin rule is <strong>not</strong> expressed here or in the URL
 * matchers: "you may edit this recipe because you wrote it" depends on a row in
 * the database, so {@code RecipeService} performs it after loading the recipe.
 * See {@code docs/optional-authentication.md}.
 * <p>
 * Nothing here holds a {@code try/catch} or checks a result for null.
 * {@code RecipeService} returns a response record or throws, and
 * {@code ApiExceptionHandler} maps each typed exception to its status: 404 for an
 * unknown recipe id, 403 for someone else's recipe, 400 for a payload naming a
 * category, tag, ingredient, unit or media row that does not exist.
 */
@Log4j2
@RestController
public class RecipeController implements RecipesApi {

    private final RecipeService recipeService;

    public RecipeController(RecipeService recipeService) {
        this.recipeService = recipeService;
    }

    /**
     * Paginated summaries, filtered and ordered by {@link RecipeSearchRequest} —
     * which Spring binds from the query string field by field, so the criteria are
     * still {@code ?title=tarte&tagName=rapide} on the wire.
     * <p>
     * Every criterion is optional and so is all of them: an unfiltered call is the
     * home page, and answers with the newest recipes.
     * <p>
     * <strong>Not generated.</strong> It returns a {@code Page<RecipeSummaryResponse>},
     * and the generator copies the response type into the class literal of an
     * {@code @Schema(implementation = ...)} annotation, where a generic type is not
     * valid Java. So the {@code x-internal} entry in {@code openapi.yaml} keeps it
     * out of {@link RecipesApi}, and the annotations a generated method would have
     * carried are written here instead. {@code OpenApiContractTest} compares what
     * they produce with that entry, which is what keeps this method honest.
     * <p>
     * {@code @PageableDefault} carries the page size only. The <em>sort</em> is
     * deliberately not left to {@code Pageable}: this listing pages over a
     * hand-written JPQL projection and Spring would append whatever property a
     * caller named straight into it, so {@code ?sort=bogus} answers 500 and
     * {@code ?sort=author.password} orders by a column no response exposes.
     * {@code sortBy} is an enum instead, and an unknown value is a 400. See
     * {@code RecipeSortField}.
     */
    @Operation(operationId = "searchRecipes", tags = "Recipes", summary = "Search recipes",
            responses = {
                    // No content here: springdoc builds it from the return type and the produces below, which is
                    // the one place a generic Page<T> can be described. See above on why this method is not generated.
                    @ApiResponse(responseCode = "200", description = "A page of recipe summaries."),
                    @ApiResponse(responseCode = "400", description = "Invalid input.",
                            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                    schema = @Schema(implementation = ProblemDetail.class)))})
    @GetMapping(value = "/recipes",
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
    public ResponseEntity<Page<RecipeSummaryResponse>> searchRecipes(
            @Valid @ParameterObject RecipeSearchRequest request,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(recipeService.search(request, pageable));
    }

    @Override
    public ResponseEntity<RecipeResponse> getRecipe(String id) {
        return ResponseEntity.ok(recipeService.findById(id));
    }

    @Override
    public ResponseEntity<RecipeResponse> createRecipe(CreateRecipeRequest request) {
        log.info("Creating a recipe");
        RecipeResponse created = recipeService.create(request);
        return ResponseEntity.created(URI.create("/recipes/" + created.id())).body(created);
    }

    /** Partial: an absent field is left alone, a present collection replaces the whole collection. */
    @Override
    public ResponseEntity<RecipeResponse> updateRecipe(String id, UpdateRecipeRequest request) {
        log.info("Updating recipe {}", id);
        return ResponseEntity.ok(recipeService.update(id, request));
    }

    /**
     * Cascades to the introduction, steps, groups, ingredient lines and picture
     * links; merely dereferences ingredients, tags, categories and media.
     * <p>
     * 404 if the id names no recipe and 403 if it is not the caller's, both raised
     * by {@code RecipeService} before anything is removed — so a 204 here really
     * does mean the row is gone.
     */
    @Override
    public ResponseEntity<Void> deleteRecipe(String id) {
        log.info("Deleting recipe {}", id);
        recipeService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
