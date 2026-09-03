package ilenreste.unpeu.recettesback.controllers.reference;

import ilenreste.unpeu.recettesback.entities.reference.UnitEntity;
import ilenreste.unpeu.recettesback.mappers.reference.ReferenceMapper;
import ilenreste.unpeu.recettesback.models.reference.requests.UnitRequest;
import ilenreste.unpeu.recettesback.models.reference.responses.UnitResponse;
import ilenreste.unpeu.recettesback.services.reference.UnitService;
import jakarta.validation.Valid;
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
 */
@RequestMapping("/units")
@RestController
public class UnitController {

    private final UnitService unitService;
    private final ReferenceMapper mapper;

    public UnitController(UnitService unitService, ReferenceMapper mapper) {
        this.unitService = unitService;
        this.mapper = mapper;
    }

    @GetMapping
    public ResponseEntity<List<UnitResponse>> list() {
        return ResponseEntity.ok(mapper.toUnitResponses(unitService.findAll()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UnitResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(mapper.toResponse(unitService.findById(id)));
    }

    @PostMapping
    public ResponseEntity<UnitResponse> create(@Valid @RequestBody UnitRequest request) {
        UnitEntity created = unitService.createUnit(request);
        return ResponseEntity.created(URI.create("/units/" + created.getId()))
                .body(mapper.toResponse(created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<UnitResponse> update(@PathVariable String id,
                                               @Valid @RequestBody UnitRequest request) {
        return ResponseEntity.ok(mapper.toResponse(unitService.updateUnit(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        unitService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
