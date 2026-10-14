package ilenreste.unpeu.recettesback.services.reference;

import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
import ilenreste.unpeu.recettesback.entities.reference.IngredientEntity;
import ilenreste.unpeu.recettesback.exceptions.InvalidReferenceException;
import ilenreste.unpeu.recettesback.mappers.reference.ReferenceMapper;
import ilenreste.unpeu.recettesback.models.reference.requests.IngredientRequest;
import ilenreste.unpeu.recettesback.models.reference.requests.IngredientSearchRequest;
import ilenreste.unpeu.recettesback.models.reference.requests.IngredientSortField;
import ilenreste.unpeu.recettesback.models.reference.responses.IngredientResponse;
import ilenreste.unpeu.recettesback.repositories.media.MediaRepository;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipesRepository;
import ilenreste.unpeu.recettesback.repositories.reference.IngredientsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

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

    /**
     * What the service must hand the repository: the caller's page number and size,
     * and a sort built from {@link IngredientSortField} rather than whatever arrived
     * on the incoming {@code Pageable}. Asserting this exact value is what pins the
     * fix - a query string can no longer choose the ordering.
     */
    private static final Pageable SORTED_BY_NAME =
            PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "normalizedName"));

    private static IngredientSearchRequest searchFor(String namePrefix) {
        return new IngredientSearchRequest(namePrefix, null, null);
    }

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
                mediaRepository, new ReferenceNameNormalizer(), new ReferenceMapper());
    }

    @Test
    void attachesAnIconThatAlreadyExists() {
        when(ingredientsRepository.findByNormalizedName(anyString())).thenReturn(Optional.empty());
        when(ingredientsRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        MediaEntity icon = new MediaEntity();
        when(mediaRepository.findById("media-1")).thenReturn(Optional.of(icon));

        ingredientService.createIngredient(new IngredientRequest("Farine", "media-1"));

        assertThat(savedIngredient().getIcon()).isSameAs(icon);
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

        assertThat(ingredientService.createIngredient(new IngredientRequest("Sel", null)).iconMediaId())
                .isNull();
        assertThat(ingredientService.createIngredient(new IngredientRequest("Poivre", "  ")).iconMediaId())
                .isNull();
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

        IngredientResponse updated = ingredientService.updateIngredient("ing-1",
                new IngredientRequest("Farine T55", null));

        assertThat(updated.iconMediaId()).isNull();
        assertThat(updated.name()).isEqualTo("Farine T55");
    }

    /** The entity handed to {@code save}, which is what actually reached the database. */
    private IngredientEntity savedIngredient() {
        ArgumentCaptor<IngredientEntity> saved = ArgumentCaptor.forClass(IngredientEntity.class);
        verify(ingredientsRepository).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void setsTheIconBeforeTheInsert_notByDirtyCheckingAfterwards() {
        // The previous version saved the bare name first and mutated the row afterwards, relying on
        // the flush to notice. It worked only because icon_media_id is nullable; the first NOT NULL
        // column added this way would have failed on insert from code that reads as if it works.
        when(ingredientsRepository.findByNormalizedName(anyString())).thenReturn(Optional.empty());
        when(ingredientsRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        MediaEntity icon = new MediaEntity();
        when(mediaRepository.findById("media-1")).thenReturn(Optional.of(icon));

        ingredientService.createIngredient(new IngredientRequest("Farine", "media-1"));

        // One save, carrying everything - not an insert followed by an update.
        assertThat(savedIngredient().getIcon()).isSameAs(icon);
    }

    @Test
    void searchesTheNormalizedColumn_soAccentsAndCaseDoNotMatter() {
        Pageable pageable = PageRequest.of(0, 20);
        when(ingredientsRepository.findByNormalizedNameStartingWith(anyString(), any()))
                .thenReturn(Page.empty());

        ingredientService.search(searchFor("Œu"), pageable);

        // Prefix, not substring: a trailing wildcard uses the index on normalized_name and a
        // leading one cannot.
        verify(ingredientsRepository).findByNormalizedNameStartingWith(eq("œu"), eq(SORTED_BY_NAME));
    }

    @Test
    void returnsEverythingWhenNoQueryIsGiven() {
        Pageable pageable = PageRequest.of(0, 20);
        when(ingredientsRepository.findAll(SORTED_BY_NAME)).thenReturn(Page.empty());

        ingredientService.search(searchFor(null), pageable);
        ingredientService.search(searchFor("   "), pageable);

        verify(ingredientsRepository, org.mockito.Mockito.times(2)).findAll(SORTED_BY_NAME);
        verify(ingredientsRepository, never())
                .findByNormalizedNameStartingWith(anyString(), any());
    }

    /**
     * The defect this closes: {@code @PageableDefault} only supplies a default, so a
     * supplied {@code ?sort=} reached Spring Data's property resolution directly -
     * {@code ?sort=bogus} answered 500 on a public endpoint, and
     * {@code ?sort=createdBy.password} ordered the list by the bcrypt column. The
     * service must therefore ignore whatever sort arrives on the Pageable.
     */
    @Test
    void ignoresTheSortOnTheIncomingPageable_soAQueryStringCannotChooseTheOrdering() {
        Pageable hostile = PageRequest.of(0, 20, Sort.by("createdBy.password"));
        when(ingredientsRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());

        ingredientService.search(searchFor(null), hostile);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(ingredientsRepository).findAll(captor.capture());
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "normalizedName"));
        // The page number and size still come from the caller - only the sort is overridden.
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    void honoursAnExplicitSortFieldAndDirection() {
        when(ingredientsRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());

        ingredientService.search(
                new IngredientSearchRequest(null, IngredientSortField.CREATED_AT, Sort.Direction.DESC),
                PageRequest.of(2, 5));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(ingredientsRepository).findAll(captor.capture());
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(5);
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
