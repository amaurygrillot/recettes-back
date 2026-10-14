package ilenreste.unpeu.recettesback.controllers.reference;

import ilenreste.unpeu.recettesback.models.reference.requests.IngredientRequest;
import ilenreste.unpeu.recettesback.models.reference.requests.IngredientSearchRequest;
import ilenreste.unpeu.recettesback.models.reference.responses.IngredientResponse;
import ilenreste.unpeu.recettesback.services.reference.IngredientService;
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
 * Ingredients. Note the asymmetry: <strong>creating</strong> one is open to any
 * authenticated user, because a recipe cannot be written without the ingredients
 * it uses, but <strong>editing and deleting</strong> are admin-only.
 * <p>
 * Unlike the other three reference endpoints this one is paginated: ingredients
 * are the table that grows, and returning all of them to power an autocomplete
 * gets worse every week.
 * <p>
 * No {@code try/catch} and no result checking: {@code IngredientService} returns
 * a response record or throws, and {@code ApiExceptionHandler} turns each typed
 * exception into its status — 404 for an unknown id, 409 for a duplicate name or
 * an ingredient a recipe still uses, 400 for an {@code iconMediaId} naming no
 * media row.
 */
@Log4j2
@RequestMapping("/ingredients")
@RestController
public class IngredientController {

    private final IngredientService ingredientService;

    public IngredientController(IngredientService ingredientService) {
        this.ingredientService = ingredientService;
    }

    /**
     * Paginated, filtered by {@link IngredientSearchRequest} — which Spring binds
     * from the query string field by field, so the criteria are still
     * {@code ?namePrefix=oeuf} on the wire.
     * <p>
     * {@code @PageableDefault} carries the page size only. The <em>sort</em> is
     * deliberately not left to {@code Pageable}: it only supplies a default, so a
     * supplied {@code ?sort=} overrode it and reached Spring Data's property
     * resolution directly — {@code ?sort=bogus} answered 500 on a public endpoint
     * and {@code ?sort=createdBy.password} ordered the list by the bcrypt column.
     * {@code sortBy} is an enum instead, and an unknown value is a 400. See
     * {@code IngredientSortField}, and {@code RecipeController.search}, which has
     * always done it this way.
     */
    @GetMapping
    public ResponseEntity<Page<IngredientResponse>> search(
            @Valid IngredientSearchRequest request,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ingredientService.search(request, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<IngredientResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(ingredientService.get(id));
    }

    @PostMapping
    public ResponseEntity<IngredientResponse> create(@Valid @RequestBody IngredientRequest request) {
        log.info("Creating an ingredient");
        IngredientResponse created = ingredientService.createIngredient(request);
        return ResponseEntity.created(URI.create("/ingredients/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<IngredientResponse> update(@PathVariable String id,
                                                     @Valid @RequestBody IngredientRequest request) {
        log.info("Updating ingredient {}", id);
        return ResponseEntity.ok(ingredientService.updateIngredient(id, request));
    }

    /** 409 rather than a cascade if any recipe still uses it. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        log.info("Deleting ingredient {}", id);
        ingredientService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
