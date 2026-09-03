package ilenreste.unpeu.recettesback.services.reference;

import ilenreste.unpeu.recettesback.entities.reference.CategoryEntity;
import ilenreste.unpeu.recettesback.exceptions.ResourceConflictException;
import ilenreste.unpeu.recettesback.exceptions.ResourceNotFoundException;
import ilenreste.unpeu.recettesback.models.reference.requests.ReferenceNameRequest;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipesRepository;
import ilenreste.unpeu.recettesback.repositories.reference.CategoriesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Sort;

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
 * Exercises the rules shared by all four reference services through one of them, since
 * {@link AbstractReferenceService} is where the create / rename / delete logic actually lives.
 */
class CategoryServiceTest {

    private CategoriesRepository categoriesRepository;
    private RecipesRepository recipesRepository;
    private CategoryService categoryService;

    @BeforeEach
    void setUp() {
        categoriesRepository = mock(CategoriesRepository.class);
        recipesRepository = mock(RecipesRepository.class);
        categoryService = new CategoryService(categoriesRepository, recipesRepository,
                new ReferenceNameNormalizer());
    }

    private CategoryEntity existing(String id, String name, String normalized) {
        CategoryEntity entity = new CategoryEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setNormalizedName(normalized);
        return entity;
    }

    @Test
    void storesTheNameAsTypedAndTheNormalizedFormAlongsideIt() {
        when(categoriesRepository.findByNormalizedName(anyString())).thenReturn(Optional.empty());
        when(categoriesRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        categoryService.createCategory(new ReferenceNameRequest("  Plat   Principal "));

        ArgumentCaptor<CategoryEntity> saved = ArgumentCaptor.forClass(CategoryEntity.class);
        verify(categoriesRepository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Plat   Principal");
        assertThat(saved.getValue().getNormalizedName()).isEqualTo("plat principal");
    }

    @Test
    void refusesADuplicate_andNamesTheRowItCollidedWith() {
        // The message is the point: a client that is told which row already exists can select it,
        // where a bare "409 Conflict" leaves it retrying blindly.
        when(categoriesRepository.findByNormalizedName("dessert"))
                .thenReturn(Optional.of(existing("cat-1", "Dessert", "dessert")));

        assertThatThrownBy(() -> categoryService.createCategory(new ReferenceNameRequest("DESSERT")))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("Dessert")
                .hasMessageContaining("cat-1");

        verify(categoriesRepository, never()).save(any());
    }

    @Test
    void allowsRenamingARowToADifferentSpellingOfItsOwnName() {
        // "oeuf" -> "Oeuf" normalizes to the same value, so the collision check must not fire
        // against the row being renamed. This is how a display name gets tidied up.
        CategoryEntity entity = existing("cat-1", "dessert", "dessert");
        when(categoriesRepository.findById("cat-1")).thenReturn(Optional.of(entity));
        when(categoriesRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        categoryService.updateCategory("cat-1", new ReferenceNameRequest("Dessert"));

        assertThat(entity.getName()).isEqualTo("Dessert");
        assertThat(entity.getNormalizedName()).isEqualTo("dessert");
        verify(categoriesRepository, never()).findByNormalizedName(anyString());
    }

    @Test
    void refusesARenameOntoAnotherExistingRow() {
        when(categoriesRepository.findById("cat-1"))
                .thenReturn(Optional.of(existing("cat-1", "Entree", "entree")));
        when(categoriesRepository.findByNormalizedName("dessert"))
                .thenReturn(Optional.of(existing("cat-2", "Dessert", "dessert")));

        assertThatThrownBy(() -> categoryService.updateCategory("cat-1", new ReferenceNameRequest("Dessert")))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("cat-2");
    }

    @Test
    void reportsAnUnknownIdAsNotFound() {
        when(categoriesRepository.findById("nope")).thenReturn(Optional.empty());

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
        CategoryEntity entity = existing("cat-1", "Dessert", "dessert");
        when(categoriesRepository.findById("cat-1")).thenReturn(Optional.of(entity));
        when(recipesRepository.isCategoryUsed("cat-1")).thenReturn(true);

        assertThatThrownBy(() -> categoryService.delete("cat-1"))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("still used");

        verify(categoriesRepository, never()).delete(any());
    }

    @Test
    void deletesACategoryNoRecipeReferences() {
        CategoryEntity entity = existing("cat-1", "Dessert", "dessert");
        when(categoriesRepository.findById("cat-1")).thenReturn(Optional.of(entity));
        when(recipesRepository.isCategoryUsed("cat-1")).thenReturn(false);

        categoryService.delete("cat-1");

        verify(categoriesRepository).delete(entity);
    }

    @Test
    void listsEverythingSortedByName_soTheOutputIsStable() {
        when(categoriesRepository.findAll(any(Sort.class))).thenReturn(List.of());

        categoryService.findAll();

        ArgumentCaptor<Sort> sort = ArgumentCaptor.forClass(Sort.class);
        verify(categoriesRepository).findAll(sort.capture());
        assertThat(sort.getValue()).isEqualTo(Sort.by("name"));
    }
}
