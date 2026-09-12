package ilenreste.unpeu.recettesback.controllers.reference;

import ilenreste.unpeu.recettesback.models.reference.requests.UnitRequest;
import ilenreste.unpeu.recettesback.models.reference.responses.UnitResponse;
import ilenreste.unpeu.recettesback.services.reference.UnitService;
import jakarta.validation.Valid;
import lombok.extern.log4j.Log4j2;
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
import java.util.List;

/**
 * Units of measure. Admin-curated by analogy with categories and tags: a small
 * closed set that shapes how every recipe reads, and not something a user
 * legitimately needs to extend mid-recipe.
 * <p>
 * No {@code try/catch} and no result checking: {@code UnitService} returns a
 * response record or throws, and {@code ApiExceptionHandler} turns each typed
 * exception into its status — 404 for an unknown id, 409 for a duplicate name or
 * a unit a recipe still uses.
 */
@Log4j2
@RequestMapping("/units")
@RestController
public class UnitController {

    private final UnitService unitService;

    public UnitController(UnitService unitService) {
        this.unitService = unitService;
    }

    @GetMapping
    public ResponseEntity<List<UnitResponse>> list() {
        return ResponseEntity.ok(unitService.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<UnitResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(unitService.get(id));
    }

    @PostMapping
    public ResponseEntity<UnitResponse> create(@Valid @RequestBody UnitRequest request) {
        log.info("Creating a unit");
        UnitResponse created = unitService.createUnit(request);
        return ResponseEntity.created(URI.create("/units/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<UnitResponse> update(@PathVariable String id,
                                               @Valid @RequestBody UnitRequest request) {
        log.info("Updating unit {}", id);
        return ResponseEntity.ok(unitService.updateUnit(id, request));
    }

    /** 409 rather than a cascade if any recipe still uses it. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        log.info("Deleting unit {}", id);
        unitService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
