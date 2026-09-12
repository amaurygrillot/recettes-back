package ilenreste.unpeu.recettesback.controllers.recipes;

import ilenreste.unpeu.recettesback.models.recipes.requests.CreateRecipeRequest;
import ilenreste.unpeu.recettesback.models.recipes.requests.RecipeSearchRequest;
import ilenreste.unpeu.recettesback.models.recipes.requests.UpdateRecipeRequest;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeResponse;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeSummaryResponse;
import ilenreste.unpeu.recettesback.services.recipes.RecipeService;
import jakarta.validation.Valid;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Recipes. Reads are public; writing needs a login, and editing or deleting
 * needs to be the author or an admin.
 * <p>
 * That last rule is <strong>not</strong> expressed here or in the URL matchers:
 * "you may edit this recipe because you wrote it" depends on a row in the
 * database, so {@code RecipeService} performs it after loading the recipe. See
 * {@code docs/optional-authentication.md}.
 * <p>
 * Nothing here holds a {@code try/catch} or checks a result for null.
 * {@code RecipeService} returns a response record or throws, and
 * {@code ApiExceptionHandler} maps each typed exception to its status: 404 for an
 * unknown recipe id, 403 for someone else's recipe, 400 for a payload naming a
 * category, tag, ingredient, unit or media row that does not exist.
 */
@Log4j2
@RequestMapping("/recipes")
@RestController
public class RecipeController {

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
     * {@code @PageableDefault} carries the page size only. The <em>sort</em> is
     * deliberately not left to {@code Pageable}: this listing pages over a
     * hand-written JPQL projection and Spring would append whatever property a
     * caller named straight into it, so {@code ?sort=bogus} answers 500 and
     * {@code ?sort=author.password} orders by a column no response exposes.
     * {@code sortBy} is an enum instead, and an unknown value is a 400. See
     * {@code RecipeSortField}.
     */
    @GetMapping
    public ResponseEntity<Page<RecipeSummaryResponse>> search(
            @Valid RecipeSearchRequest request,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(recipeService.search(request, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RecipeResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(recipeService.findById(id));
    }

    @PostMapping
    public ResponseEntity<RecipeResponse> create(@Valid @RequestBody CreateRecipeRequest request) {
        log.info("Creating a recipe");
        RecipeResponse created = recipeService.create(request);
        return ResponseEntity.created(URI.create("/recipes/" + created.id())).body(created);
    }

    /** Partial: an absent field is left alone, a present collection replaces the whole collection. */
    @PutMapping("/{id}")
    public ResponseEntity<RecipeResponse> update(@PathVariable String id,
                                                 @Valid @RequestBody UpdateRecipeRequest request) {
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
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        log.info("Deleting recipe {}", id);
        recipeService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
