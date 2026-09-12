package ilenreste.unpeu.recettesback.services.reference;

import ilenreste.unpeu.recettesback.entities.reference.CategoryEntity;
import ilenreste.unpeu.recettesback.entities.reference.CategoryRelationEntity;
import ilenreste.unpeu.recettesback.entities.reference.CategoryType;
import ilenreste.unpeu.recettesback.exceptions.InvalidInputException;
import ilenreste.unpeu.recettesback.exceptions.InvalidReferenceException;
import ilenreste.unpeu.recettesback.exceptions.ResourceConflictException;
import ilenreste.unpeu.recettesback.exceptions.ResourceNotFoundException;
import ilenreste.unpeu.recettesback.mappers.reference.ReferenceMapper;
import ilenreste.unpeu.recettesback.models.reference.requests.CategoryRequest;
import ilenreste.unpeu.recettesback.models.reference.responses.CategoryResponse;
import ilenreste.unpeu.recettesback.models.reference.responses.CategoryTreeResponse;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipesRepository;
import ilenreste.unpeu.recettesback.repositories.reference.CategoriesRepository;
import ilenreste.unpeu.recettesback.repositories.reference.CategoryRelationsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Two things at once, because they live in the same class.
 * <p>
 * The create / rename / delete rules shared by all four reference services are
 * exercised through this one, since {@link AbstractReferenceService} is where
 * they actually live. On top of that sit the rules only categories have: the
 * parent edge, the mirrored {@code categoryType}, the cycle refusal, and the
 * three hierarchy reads.
 */
class CategoryServiceTest {

    private CategoriesRepository categoriesRepository;
    private CategoryRelationsRepository relationsRepository;
    private RecipesRepository recipesRepository;
    private CategoryService categoryService;

    /** Every category the fake repository knows about, in insertion order. */
    private final List<CategoryEntity> stored = new ArrayList<>();
    /** Edges as {@code {categoryId, superiorCategoryId}}, self-referencing for roots. */
    private final List<Object[]> edges = new ArrayList<>();

    @BeforeEach
    void setUp() {
        categoriesRepository = mock(CategoriesRepository.class);
        relationsRepository = mock(CategoryRelationsRepository.class);
        recipesRepository = mock(RecipesRepository.class);
        categoryService = new CategoryService(categoriesRepository, relationsRepository,
                recipesRepository, new ReferenceNameNormalizer(), new ReferenceMapper());

        when(categoriesRepository.findAll(any(Sort.class))).thenAnswer(invocation -> List.copyOf(stored));
        when(categoriesRepository.findById(anyString())).thenAnswer(invocation -> stored.stream()
                .filter(category -> category.getId().equals(invocation.getArgument(0)))
                .findFirst());
        when(categoriesRepository.findByNormalizedName(anyString())).thenAnswer(invocation -> stored.stream()
                .filter(category -> category.getNormalizedName().equals(invocation.getArgument(0)))
                .findFirst());
        when(categoriesRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        // The default method under test is the real one; only findAllEdges is faked.
        when(relationsRepository.findAllEdges()).thenAnswer(invocation -> List.copyOf(edges));
        when(relationsRepository.superiorIdsByCategoryId()).thenCallRealMethod();
        when(relationsRepository.findById(anyString())).thenReturn(Optional.empty());
        when(relationsRepository.save(any())).thenAnswer(invocation -> {
            CategoryRelationEntity relation = invocation.getArgument(0);
            edges.removeIf(edge -> edge[0].equals(relation.getCategory().getId()));
            edges.add(new Object[]{relation.getCategory().getId(), relation.getSuperiorCategory().getId()});
            return relation;
        });
    }

    /** Registers a category as if it had already been created, with its edge. */
    private CategoryEntity given(String id, String name, String normalized, String superiorId) {
        CategoryEntity entity = new CategoryEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setNormalizedName(normalized);
        entity.setCategoryType(superiorId.equals(id) ? CategoryType.MAIN : CategoryType.SUB);
        stored.add(entity);
        edges.add(new Object[]{id, superiorId});
        return entity;
    }

    /** {@code create} generates no id in a unit test, so one is stamped on save. */
    private void assignIdOnSave(String id) {
        when(categoriesRepository.save(any())).thenAnswer(invocation -> {
            CategoryEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(id);
                stored.add(entity);
            }
            return entity;
        });
    }

    @Test
    void storesTheNameAsTypedAndTheNormalizedFormAlongsideIt() {
        assignIdOnSave("cat-1");

        categoryService.createCategory(new CategoryRequest("  Plat   Principal ", null));

        ArgumentCaptor<CategoryEntity> saved = ArgumentCaptor.forClass(CategoryEntity.class);
        verify(categoriesRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Plat   Principal");
        assertThat(saved.getValue().getNormalizedName()).isEqualTo("plat principal");
    }

    @Test
    void refusesADuplicate_andNamesTheRowItCollidedWith() {
        // The message is the point: a client that is told which row already exists can select it,
        // where a bare "409 Conflict" leaves it retrying blindly.
        given("cat-1", "Dessert", "dessert", "cat-1");

        assertThatThrownBy(() -> categoryService.createCategory(new CategoryRequest("DESSERT", null)))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("Dessert")
                .hasMessageContaining("cat-1");

        verify(categoriesRepository, never()).save(any());
    }

    @Test
    void allowsRenamingARowToADifferentSpellingOfItsOwnName() {
        // "dessert" -> "Dessert" normalizes to the same value, so the collision check must not fire
        // against the row being renamed. This is how a display name gets tidied up.
        CategoryEntity entity = given("cat-1", "dessert", "dessert", "cat-1");

        categoryService.updateCategory("cat-1", new CategoryRequest("Dessert", null));

        assertThat(entity.getName()).isEqualTo("Dessert");
        assertThat(entity.getNormalizedName()).isEqualTo("dessert");
    }

    @Test
    void refusesARenameOntoAnotherExistingRow() {
        given("cat-1", "Entree", "entree", "cat-1");
        given("cat-2", "Dessert", "dessert", "cat-2");

        assertThatThrownBy(() -> categoryService.updateCategory("cat-1", new CategoryRequest("Dessert", null)))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("cat-2");
    }

    @Test
    void reportsAnUnknownIdAsNotFound() {
        assertThatThrownBy(() -> categoryService.findById("nope"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("category");
        assertThatThrownBy(() -> categoryService.delete("nope"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void refusesToDeleteACategoryStillUsedByARecipe() {
        // 409, never a cascade. Silently gutting other people's recipes because an admin tidied the
        // category list is not an acceptable side effect of a DELETE.
        CategoryEntity entity = given("cat-1", "Dessert", "dessert", "cat-1");
        when(recipesRepository.isCategoryUsed("cat-1")).thenReturn(true);

        assertThatThrownBy(() -> categoryService.delete("cat-1"))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("still used");

        verify(categoriesRepository, never()).delete(entity);
    }

    @Test
    void refusesToDeleteACategoryThatStillHasSubcategories() {
        // Otherwise the children's edges would name a row that no longer exists: a subtree
        // unreachable from any root and invisible to every hierarchy query.
        given("cat-1", "Desserts", "desserts", "cat-1");
        given("cat-2", "Tartes", "tartes", "cat-1");
        when(relationsRepository.hasSubcategories("cat-1")).thenReturn(true);

        assertThatThrownBy(() -> categoryService.delete("cat-1"))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("subcategories");

        verify(categoriesRepository, never()).delete(any());
        verify(relationsRepository, never()).deleteById(anyString());
    }

    @Test
    void deletesACategoryNoRecipeReferences_alongWithItsOwnEdge() {
        CategoryEntity entity = given("cat-1", "Dessert", "dessert", "cat-1");
        when(recipesRepository.isCategoryUsed("cat-1")).thenReturn(false);
        when(relationsRepository.hasSubcategories("cat-1")).thenReturn(false);

        categoryService.delete("cat-1");

        // The edge has to go explicitly: for a main category it points at the row being deleted.
        verify(relationsRepository).deleteById("cat-1");
        verify(categoriesRepository).delete(entity);
    }

    @Test
    void listsEverythingSortedByName_soTheOutputIsStable() {
        categoryService.findAll();

        ArgumentCaptor<Sort> sort = ArgumentCaptor.forClass(Sort.class);
        verify(categoriesRepository).findAll(sort.capture());
        assertThat(sort.getValue()).isEqualTo(Sort.by("name"));
    }

    @Test
    void aCategoryWithNoSuperiorIsAMainCategory_andItsEdgePointsAtItself() {
        assignIdOnSave("cat-1");

        CategoryResponse created = categoryService.createCategory(new CategoryRequest("Desserts", null));

        assertThat(created.categoryType()).isEqualTo(CategoryType.MAIN);
        // Null rather than the stored self-reference: the schema device stays inside the server.
        assertThat(created.superiorCategoryId()).isNull();
        assertThat(edges).anySatisfy(edge -> assertThat(edge).containsExactly("cat-1", "cat-1"));
    }

    @Test
    void aCategoryWithASuperiorIsASubcategory() {
        given("cat-1", "Desserts", "desserts", "cat-1");
        assignIdOnSave("cat-2");

        CategoryResponse created = categoryService.createCategory(new CategoryRequest("Tartes", "cat-1"));

        assertThat(created.categoryType()).isEqualTo(CategoryType.SUB);
        assertThat(created.superiorCategoryId()).isEqualTo("cat-1");
    }

    @Test
    void refusesASuperiorThatNamesNoCategory() {
        // 400 and not 404: /categories is perfectly reachable, it is the payload that is wrong.
        assignIdOnSave("cat-1");

        assertThatThrownBy(() -> categoryService.createCategory(new CategoryRequest("Tartes", "ghost")))
                .isInstanceOf(InvalidReferenceException.class)
                .hasMessageContaining("superiorCategoryId");
    }

    @Test
    void refusesToFileACategoryUnderItsOwnDescendant() {
        // A cycle makes every walk below non-terminating - a hang, not an error - and detaches the
        // whole subtree from any root.
        given("cat-1", "Desserts", "desserts", "cat-1");
        given("cat-2", "Tartes", "tartes", "cat-1");
        given("cat-3", "Tartes aux pommes", "tartes aux pommes", "cat-2");

        assertThatThrownBy(() -> categoryService.updateCategory("cat-1", new CategoryRequest("Desserts", "cat-3")))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("subcategories");
    }

    @Test
    void promotingASubcategoryToARootRewritesBothTheEdgeAndTheType() {
        CategoryEntity sub = given("cat-2", "Tartes", "tartes", "cat-1");
        given("cat-1", "Desserts", "desserts", "cat-1");

        CategoryResponse updated = categoryService.updateCategory("cat-2", new CategoryRequest("Tartes", null));

        assertThat(updated.categoryType()).isEqualTo(CategoryType.MAIN);
        assertThat(updated.superiorCategoryId()).isNull();
        assertThat(sub.getCategoryType()).isEqualTo(CategoryType.MAIN);
    }

    @Test
    void returnsEveryDescendantAtEveryDepth_butNotTheCategoryItself() {
        given("cat-1", "Desserts", "desserts", "cat-1");
        given("cat-2", "Tartes", "tartes", "cat-1");
        given("cat-3", "Tartes aux pommes", "tartes aux pommes", "cat-2");
        given("cat-4", "Entrees", "entrees", "cat-4");

        assertThat(categoryService.findSubcategories("cat-1"))
                .extracting(CategoryResponse::id)
                .containsExactly("cat-2", "cat-3");
    }

    @Test
    void returnsTheAncestorChainNearestFirst_andNothingForAMainCategory() {
        given("cat-1", "Desserts", "desserts", "cat-1");
        given("cat-2", "Tartes", "tartes", "cat-1");
        given("cat-3", "Tartes aux pommes", "tartes aux pommes", "cat-2");

        assertThat(categoryService.findSuperiorCategories("cat-3"))
                .extracting(CategoryResponse::id)
                .containsExactly("cat-2", "cat-1");
        assertThat(categoryService.findSuperiorCategories("cat-1")).isEmpty();
    }

    @Test
    void buildsTheWholeHierarchyAsNestedRoots() {
        given("cat-1", "Desserts", "desserts", "cat-1");
        given("cat-2", "Tartes", "tartes", "cat-1");
        given("cat-3", "Tartes aux pommes", "tartes aux pommes", "cat-2");
        given("cat-4", "Entrees", "entrees", "cat-4");

        List<CategoryTreeResponse> hierarchy = categoryService.findHierarchy();

        assertThat(hierarchy).extracting(CategoryTreeResponse::id)
                .containsExactlyInAnyOrder("cat-1", "cat-4");
        CategoryTreeResponse desserts = hierarchy.stream()
                .filter(node -> node.id().equals("cat-1")).findFirst().orElseThrow();
        assertThat(desserts.subcategories()).singleElement()
                .satisfies(tartes -> {
                    assertThat(tartes.id()).isEqualTo("cat-2");
                    assertThat(tartes.subcategories()).extracting(CategoryTreeResponse::id)
                            .containsExactly("cat-3");
                });
    }

    @Test
    void reportsAnUnknownIdAsNotFoundOnBothHierarchyReads() {
        // "this category has nothing under it" and "there is no such category" are different
        // answers, and an empty list for the second would hide a client's broken link.
        assertThatThrownBy(() -> categoryService.findSubcategories("ghost"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> categoryService.findSuperiorCategories("ghost"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
