package ilenreste.unpeu.recettesback.controllers.reference;

import ilenreste.unpeu.recettesback.api.CategoriesApi;
import ilenreste.unpeu.recettesback.models.reference.requests.CategoryRequest;
import ilenreste.unpeu.recettesback.models.reference.responses.CategoryResponse;
import ilenreste.unpeu.recettesback.models.reference.responses.CategoryTreeResponse;
import ilenreste.unpeu.recettesback.services.reference.CategoryService;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Categories: the taxonomy that drives navigation, and the hierarchy they form.
 * Reads are public; every write is admin-only, enforced by the URL rules in
 * {@code SecurityFilterConfig}.
 * <p>
 * The routes, parameters, status codes and security of every method here are
 * declared in {@code openapi.yaml} and reach this class through the generated
 * {@link CategoriesApi}; see {@code docs/openapi.md}.
 * <p>
 * The whole list is returned unpaginated, deliberately: this is a small, closed
 * set, and a category list long enough to need paging is the signal that
 * categories and tags have merged in practice.
 * <p>
 * Nothing here holds a {@code try/catch} or an {@code if (result == null)}.
 * {@code CategoryService} returns a response record or throws — see its "what a
 * caller may assume" contract — and the exception-to-status mapping is
 * centralised in {@code ApiExceptionHandler}. So an unknown id answers 404, a
 * duplicate name 409, a category that still has subcategories 409, and a parent
 * id that names nothing 400, without a branch being written here for any of them.
 */
@Log4j2
@RestController
public class CategoryController implements CategoriesApi {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /** Flat, name-sorted, every category at every level. */
    @Override
    public ResponseEntity<List<CategoryResponse>> listCategories() {
        return ResponseEntity.ok(categoryService.list());
    }

    /**
     * The whole taxonomy as a nested forest — one call for a navigation menu,
     * instead of {@code GET /categories} plus a walk on the client.
     * <p>
     * Spring prefers the literal path {@code /categories/hierarchy} over the
     * {@code /categories/{id}} template, so {@code hierarchy} is never mistaken
     * for an id.
     */
    @Override
    public ResponseEntity<List<CategoryTreeResponse>> getCategoryHierarchy() {
        return ResponseEntity.ok(categoryService.findHierarchy());
    }

    @Override
    public ResponseEntity<CategoryResponse> getCategory(String id) {
        return ResponseEntity.ok(categoryService.get(id));
    }

    /**
     * Everything filed under this category, at any depth. 404 if the id names no
     * category — which is a different answer from the empty list a leaf returns.
     */
    @Override
    public ResponseEntity<List<CategoryResponse>> listSubcategories(String id) {
        return ResponseEntity.ok(categoryService.findSubcategories(id));
    }

    /**
     * The categories above this one, nearest first, up to the main category.
     * Empty for a main category.
     */
    @Override
    public ResponseEntity<List<CategoryResponse>> listParentCategories(String id) {
        return ResponseEntity.ok(categoryService.findParentCategories(id));
    }

    @Override
    public ResponseEntity<CategoryResponse> createCategory(CategoryRequest request) {
        log.info("Creating a category");
        CategoryResponse created = categoryService.createCategory(request);
        return ResponseEntity.created(URI.create("/categories/" + created.id())).body(created);
    }

    /** Full replacement: an absent {@code parentCategoryId} promotes the category to a root. */
    @Override
    public ResponseEntity<CategoryResponse> updateCategory(String id, CategoryRequest request) {
        log.info("Updating category {}", id);
        return ResponseEntity.ok(categoryService.updateCategory(id, request));
    }

    /** 409 rather than a cascade if any recipe still uses it, or if it still has subcategories. */
    @Override
    public ResponseEntity<Void> deleteCategory(String id) {
        log.info("Deleting category {}", id);
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
