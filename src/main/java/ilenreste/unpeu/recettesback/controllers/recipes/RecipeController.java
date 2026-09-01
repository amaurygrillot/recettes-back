package ilenreste.unpeu.recettesback.controllers.recipes;

import ilenreste.unpeu.recettesback.models.recipes.requests.CreateRecipeRequest;
import ilenreste.unpeu.recettesback.models.recipes.requests.UpdateRecipeRequest;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeResponse;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeSummaryResponse;
import ilenreste.unpeu.recettesback.services.recipes.RecipeService;
import jakarta.validation.Valid;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
     * Paginated summaries.
     *
     * @param categoryId single-value on purpose. Multi-category filtering —
     *                   "desserts AND rapide" — needs an AND-versus-OR decision
     *                   and a different query shape, and is out of scope until a
     *                   UI asks for it
     * @param q          matched against the title, case-insensitively, anywhere
     *                   in the string
     */
    @GetMapping
    public ResponseEntity<Page<RecipeSummaryResponse>> search(
            @RequestParam(required = false) String authorId,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String tagId,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return ResponseEntity.ok(recipeService.search(authorId, categoryId, tagId, q, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RecipeResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(recipeService.findById(id));
    }

    @PostMapping
    public ResponseEntity<RecipeResponse> create(@Valid @RequestBody CreateRecipeRequest request) {
        RecipeResponse created = recipeService.create(request);
        return ResponseEntity.created(URI.create("/recipes/" + created.id())).body(created);
    }

    /** Partial: an absent field is left alone, a present collection replaces the whole collection. */
    @PutMapping("/{id}")
    public ResponseEntity<RecipeResponse> update(@PathVariable String id,
                                                 @Valid @RequestBody UpdateRecipeRequest request) {
        return ResponseEntity.ok(recipeService.update(id, request));
    }

    /**
     * Cascades to steps, groups, ingredient lines and picture links; merely
     * dereferences ingredients, tags, categories and media.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        recipeService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
