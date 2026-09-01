package ilenreste.unpeu.recettesback.controllers.reference;

import ilenreste.unpeu.recettesback.entities.reference.IngredientEntity;
import ilenreste.unpeu.recettesback.mappers.reference.ReferenceMapper;
import ilenreste.unpeu.recettesback.models.reference.requests.IngredientRequest;
import ilenreste.unpeu.recettesback.models.reference.responses.IngredientResponse;
import ilenreste.unpeu.recettesback.services.reference.IngredientService;
import jakarta.validation.Valid;
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
 * Ingredients. Note the asymmetry: <strong>creating</strong> one is open to any
 * authenticated user, because a recipe cannot be written without the ingredients
 * it uses, but <strong>editing and deleting</strong> are admin-only.
 * <p>
 * Unlike the other three reference endpoints this one is paginated: ingredients
 * are the table that grows, and returning all of them to power an autocomplete
 * gets worse every week.
 */
@RequestMapping("/ingredients")
@RestController
public class IngredientController {

    private final IngredientService ingredientService;
    private final ReferenceMapper mapper;

    public IngredientController(IngredientService ingredientService, ReferenceMapper mapper) {
        this.ingredientService = ingredientService;
        this.mapper = mapper;
    }

    /**
     * @param q optional prefix, matched against the normalized name — which is
     *          what makes typing {@code oeuf} find {@code Œuf}. A prefix and not
     *          a substring, because a trailing wildcard can use the index and a
     *          leading one cannot
     */
    @GetMapping
    public ResponseEntity<Page<IngredientResponse>> search(
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(ingredientService.search(q, pageable).map(mapper::toResponse));
    }

    @GetMapping("/{id}")
    public ResponseEntity<IngredientResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(mapper.toResponse(ingredientService.findById(id)));
    }

    @PostMapping
    public ResponseEntity<IngredientResponse> create(@Valid @RequestBody IngredientRequest request) {
        IngredientEntity created = ingredientService.createIngredient(request);
        return ResponseEntity.created(URI.create("/ingredients/" + created.getId()))
                .body(mapper.toResponse(created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<IngredientResponse> update(@PathVariable String id,
                                                     @Valid @RequestBody IngredientRequest request) {
        return ResponseEntity.ok(mapper.toResponse(ingredientService.updateIngredient(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        ingredientService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
