package ilenreste.unpeu.recettesback.controllers.reference;

import ilenreste.unpeu.recettesback.api.IngredientsApi;
import ilenreste.unpeu.recettesback.models.reference.requests.IngredientRequest;
import ilenreste.unpeu.recettesback.models.reference.requests.IngredientSearchRequest;
import ilenreste.unpeu.recettesback.models.reference.responses.IngredientResponse;
import ilenreste.unpeu.recettesback.services.reference.IngredientService;
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
 * Ingredients. Note the asymmetry: <strong>creating</strong> one is open to any
 * authenticated user, because a recipe cannot be written without the ingredients
 * it uses, but <strong>editing and deleting</strong> are admin-only.
 * <p>
 * Everything but the search is declared in {@code openapi.yaml} and reaches this
 * class through the generated {@link IngredientsApi}; see {@code docs/openapi.md}.
 * The search is the one signature written by hand, for the reason on
 * {@link #searchIngredients}.
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
@RestController
public class IngredientController implements IngredientsApi {

    private final IngredientService ingredientService;

    public IngredientController(IngredientService ingredientService) {
        this.ingredientService = ingredientService;
    }

    /**
     * Paginated, filtered by {@link IngredientSearchRequest} — which Spring binds
     * from the query string field by field, so the criteria are still
     * {@code ?namePrefix=oeuf} on the wire.
     * <p>
     * <strong>Not generated.</strong> It returns a {@code Page<IngredientResponse>},
     * and the generator copies the response type into the class literal of an
     * {@code @Schema(implementation = ...)} annotation, where a generic type is not
     * valid Java. So the {@code x-internal} entry in {@code openapi.yaml} keeps it
     * out of {@link IngredientsApi}, and the annotations a generated method would
     * have carried are written here instead. {@code OpenApiContractTest} compares
     * what they produce with that entry, which is what keeps this method honest.
     * <p>
     * {@code @PageableDefault} carries the page size only. The <em>sort</em> is
     * deliberately not left to {@code Pageable}: it only supplies a default, so a
     * supplied {@code ?sort=} overrode it and reached Spring Data's property
     * resolution directly — {@code ?sort=bogus} answered 500 on a public endpoint
     * and {@code ?sort=createdBy.password} ordered the list by the bcrypt column.
     * {@code sortBy} is an enum instead, and an unknown value is a 400. See
     * {@code IngredientSortField}, and {@code RecipeController.searchRecipes}, which
     * has always done it this way.
     */
    @Operation(operationId = "searchIngredients", tags = "Ingredients", summary = "Search ingredients",
            responses = {
                    // No content here: springdoc builds it from the return type and the produces below, which is
                    // the one place a generic Page<T> can be described. See above on why this method is not generated.
                    @ApiResponse(responseCode = "200", description = "A page of ingredients."),
                    @ApiResponse(responseCode = "400", description = "Invalid input.",
                            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                    schema = @Schema(implementation = ProblemDetail.class)))})
    @GetMapping(value = "/ingredients",
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
    public ResponseEntity<Page<IngredientResponse>> searchIngredients(
            @Valid @ParameterObject IngredientSearchRequest request,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ingredientService.search(request, pageable));
    }

    @Override
    public ResponseEntity<IngredientResponse> getIngredient(String id) {
        return ResponseEntity.ok(ingredientService.get(id));
    }

    @Override
    public ResponseEntity<IngredientResponse> createIngredient(IngredientRequest request) {
        log.info("Creating an ingredient");
        IngredientResponse created = ingredientService.createIngredient(request);
        return ResponseEntity.created(URI.create("/ingredients/" + created.id())).body(created);
    }

    @Override
    public ResponseEntity<IngredientResponse> updateIngredient(String id, IngredientRequest request) {
        log.info("Updating ingredient {}", id);
        return ResponseEntity.ok(ingredientService.updateIngredient(id, request));
    }

    /** 409 rather than a cascade if any recipe still uses it. */
    @Override
    public ResponseEntity<Void> deleteIngredient(String id) {
        log.info("Deleting ingredient {}", id);
        ingredientService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
