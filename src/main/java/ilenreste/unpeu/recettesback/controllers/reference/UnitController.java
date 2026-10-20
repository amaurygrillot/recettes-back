package ilenreste.unpeu.recettesback.controllers.reference;

import ilenreste.unpeu.recettesback.api.UnitsApi;
import ilenreste.unpeu.recettesback.models.reference.requests.UnitRequest;
import ilenreste.unpeu.recettesback.models.reference.responses.UnitResponse;
import ilenreste.unpeu.recettesback.services.reference.UnitService;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Units of measure. Admin-curated by analogy with categories and tags: a small
 * closed set that shapes how every recipe reads, and not something a user
 * legitimately needs to extend mid-recipe.
 * <p>
 * The routes, parameters, status codes and security of every method here are
 * declared in {@code openapi.yaml} and reach this class through the generated
 * {@link UnitsApi}; see {@code docs/openapi.md}.
 * <p>
 * No {@code try/catch} and no result checking: {@code UnitService} returns a
 * response record or throws, and {@code ApiExceptionHandler} turns each typed
 * exception into its status — 404 for an unknown id, 409 for a duplicate name or
 * a unit a recipe still uses.
 */
@Log4j2
@RestController
public class UnitController implements UnitsApi {

    private final UnitService unitService;

    public UnitController(UnitService unitService) {
        this.unitService = unitService;
    }

    @Override
    public ResponseEntity<List<UnitResponse>> listUnits() {
        return ResponseEntity.ok(unitService.list());
    }

    @Override
    public ResponseEntity<UnitResponse> getUnit(String id) {
        return ResponseEntity.ok(unitService.get(id));
    }

    @Override
    public ResponseEntity<UnitResponse> createUnit(UnitRequest request) {
        log.info("Creating a unit");
        UnitResponse created = unitService.createUnit(request);
        return ResponseEntity.created(URI.create("/units/" + created.id())).body(created);
    }

    @Override
    public ResponseEntity<UnitResponse> updateUnit(String id, UnitRequest request) {
        log.info("Updating unit {}", id);
        return ResponseEntity.ok(unitService.updateUnit(id, request));
    }

    /** 409 rather than a cascade if any recipe still uses it. */
    @Override
    public ResponseEntity<Void> deleteUnit(String id) {
        log.info("Deleting unit {}", id);
        unitService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
