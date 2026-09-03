package ilenreste.unpeu.recettesback.controllers.reference;

import ilenreste.unpeu.recettesback.entities.reference.CategoryEntity;
import ilenreste.unpeu.recettesback.mappers.reference.ReferenceMapper;
import ilenreste.unpeu.recettesback.models.reference.requests.ReferenceNameRequest;
import ilenreste.unpeu.recettesback.models.reference.responses.CategoryResponse;
import ilenreste.unpeu.recettesback.services.reference.CategoryService;
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
 * Categories: the taxonomy that drives navigation. Reads are public; every write
 * is admin-only, enforced by the URL rules in {@code SecurityFilterConfig}.
 * <p>
 * The whole list is returned unpaginated, deliberately: this is a small, closed
 * set, and a category list long enough to need paging is the signal that
 * categories and tags have merged in practice.
 */
@RequestMapping("/categories")
@RestController
public class CategoryController {

    private final CategoryService categoryService;
    private final ReferenceMapper mapper;

    public CategoryController(CategoryService categoryService, ReferenceMapper mapper) {
        this.categoryService = categoryService;
        this.mapper = mapper;
    }

    @GetMapping
    public ResponseEntity<List<CategoryResponse>> list() {
        return ResponseEntity.ok(mapper.toCategoryResponses(categoryService.findAll()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CategoryResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(mapper.toResponse(categoryService.findById(id)));
    }

    @PostMapping
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody ReferenceNameRequest request) {
        CategoryEntity created = categoryService.createCategory(request);
        return ResponseEntity.created(URI.create("/categories/" + created.getId()))
                .body(mapper.toResponse(created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CategoryResponse> update(@PathVariable String id,
                                                   @Valid @RequestBody ReferenceNameRequest request) {
        return ResponseEntity.ok(mapper.toResponse(categoryService.updateCategory(id, request)));
    }

    /** 409 rather than a cascade if any recipe still uses it. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
