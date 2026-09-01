package ilenreste.unpeu.recettesback.services.reference;

import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
import ilenreste.unpeu.recettesback.entities.reference.IngredientEntity;
import ilenreste.unpeu.recettesback.exceptions.InvalidReferenceException;
import ilenreste.unpeu.recettesback.models.reference.requests.IngredientRequest;
import ilenreste.unpeu.recettesback.repositories.media.MediaRepository;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipesRepository;
import ilenreste.unpeu.recettesback.repositories.reference.IngredientsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngredientServiceTest {

    private IngredientsRepository ingredientsRepository;
    private RecipesRepository recipesRepository;
    private MediaRepository mediaRepository;
    private IngredientService ingredientService;

    @BeforeEach
    void setUp() {
        ingredientsRepository = mock(IngredientsRepository.class);
        recipesRepository = mock(RecipesRepository.class);
        mediaRepository = mock(MediaRepository.class);
        ingredientService = new IngredientService(ingredientsRepository, recipesRepository,
                mediaRepository, new ReferenceNameNormalizer());
    }

    @Test
    void attachesAnIconThatAlreadyExists() {
        when(ingredientsRepository.findByNormalizedName(anyString())).thenReturn(Optional.empty());
        when(ingredientsRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        MediaEntity icon = new MediaEntity();
        when(mediaRepository.findById("media-1")).thenReturn(Optional.of(icon));

        IngredientEntity created = ingredientService.createIngredient(
                new IngredientRequest("Farine", "media-1"));

        assertThat(created.getIcon()).isSameAs(icon);
    }

    @Test
    void rejectsAnIconIdThatNamesNoMedia_asABadPayloadRatherThanAMissingEndpoint() {
        // 400, not 404: /ingredients exists and is perfectly reachable, and a 404 here would send
        // anyone debugging the call looking for a missing route.
        when(ingredientsRepository.findByNormalizedName(anyString())).thenReturn(Optional.empty());
        when(mediaRepository.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ingredientService.createIngredient(
                new IngredientRequest("Farine", "ghost")))
                .isInstanceOf(InvalidReferenceException.class)
                .hasMessageContaining("iconMediaId")
                .hasMessageContaining("ghost");
    }

    @Test
    void treatsAnAbsentIconIdAsNoIcon() {
        when(ingredientsRepository.findByNormalizedName(anyString())).thenReturn(Optional.empty());
        when(ingredientsRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(ingredientService.createIngredient(new IngredientRequest("Sel", null)).getIcon()).isNull();
        assertThat(ingredientService.createIngredient(new IngredientRequest("Poivre", "  ")).getIcon()).isNull();
        verify(mediaRepository, never()).findById(anyString());
    }

    @Test
    void clearsTheIconWhenAnUpdateOmitsIt() {
        // This is a full update, not a patch: an absent icon means "no icon", not "leave it alone".
        IngredientEntity existing = new IngredientEntity();
        existing.setId("ing-1");
        existing.setName("Farine");
        existing.setNormalizedName("farine");
        existing.setIcon(new MediaEntity());
        when(ingredientsRepository.findById("ing-1")).thenReturn(Optional.of(existing));
        when(ingredientsRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        IngredientEntity updated = ingredientService.updateIngredient("ing-1",
                new IngredientRequest("Farine T55", null));

        assertThat(updated.getIcon()).isNull();
        assertThat(updated.getName()).isEqualTo("Farine T55");
    }

    @Test
    void searchesTheNormalizedColumn_soAccentsAndCaseDoNotMatter() {
        Pageable pageable = PageRequest.of(0, 20);
        when(ingredientsRepository.findByNormalizedNameStartingWithOrderByName(anyString(), any()))
                .thenReturn(Page.empty());

        ingredientService.search("Œu", pageable);

        // Prefix, not substring: a trailing wildcard uses the index on normalized_name and a
        // leading one cannot.
        verify(ingredientsRepository).findByNormalizedNameStartingWithOrderByName(eq("œu"), eq(pageable));
    }

    @Test
    void returnsEverythingWhenNoQueryIsGiven() {
        Pageable pageable = PageRequest.of(0, 20);
        when(ingredientsRepository.findAll(pageable)).thenReturn(Page.empty());

        ingredientService.search(null, pageable);
        ingredientService.search("   ", pageable);

        verify(ingredientsRepository, org.mockito.Mockito.times(2)).findAll(pageable);
        verify(ingredientsRepository, never())
                .findByNormalizedNameStartingWithOrderByName(anyString(), any());
    }

    @Test
    void refusesToDeleteAnIngredientStillUsedByARecipe() {
        IngredientEntity existing = new IngredientEntity();
        existing.setId("ing-1");
        when(ingredientsRepository.findById("ing-1")).thenReturn(Optional.of(existing));
        when(recipesRepository.isIngredientUsed("ing-1")).thenReturn(true);

        assertThatThrownBy(() -> ingredientService.delete("ing-1"))
                .isInstanceOf(ilenreste.unpeu.recettesback.exceptions.ResourceConflictException.class)
                .hasMessageContaining("ingredient");
    }
}
