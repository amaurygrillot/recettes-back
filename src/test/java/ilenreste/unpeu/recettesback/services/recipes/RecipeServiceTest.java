package ilenreste.unpeu.recettesback.services.recipes;

import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeEntity;
import ilenreste.unpeu.recettesback.entities.reference.CategoryEntity;
import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.exceptions.ForbiddenOperationException;
import ilenreste.unpeu.recettesback.exceptions.InvalidReferenceException;
import ilenreste.unpeu.recettesback.exceptions.ResourceNotFoundException;
import ilenreste.unpeu.recettesback.mappers.recipes.RecipeMapper;
import ilenreste.unpeu.recettesback.mappers.reference.ReferenceMapper;
import ilenreste.unpeu.recettesback.models.recipes.requests.CreateRecipeRequest;
import ilenreste.unpeu.recettesback.models.recipes.requests.RecipeIntroductionRequest;
import ilenreste.unpeu.recettesback.models.recipes.requests.RecipePictureRequest;
import ilenreste.unpeu.recettesback.models.recipes.requests.RecipeSearchRequest;
import ilenreste.unpeu.recettesback.models.recipes.requests.RecipeSortField;
import ilenreste.unpeu.recettesback.models.recipes.requests.RecipeStepRequest;
import ilenreste.unpeu.recettesback.models.recipes.requests.UpdateRecipeRequest;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeResponse;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeSummaryResponse;
import ilenreste.unpeu.recettesback.repositories.media.MediaRepository;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipesRepository;
import ilenreste.unpeu.recettesback.repositories.reference.CategoriesRepository;
import ilenreste.unpeu.recettesback.repositories.reference.CategoryRelationsRepository;
import ilenreste.unpeu.recettesback.repositories.reference.IngredientsRepository;
import ilenreste.unpeu.recettesback.repositories.reference.TagsRepository;
import ilenreste.unpeu.recettesback.repositories.reference.UnitsRepository;
import ilenreste.unpeu.recettesback.services.reference.ReferenceNameNormalizer;
import ilenreste.unpeu.recettesback.services.users.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecipeServiceTest {

    private RecipesRepository recipesRepository;
    private CurrentUserService currentUserService;
    private CategoriesRepository categoriesRepository;
    private MediaRepository mediaRepository;
    private CategoryRelationsRepository categoryRelationsRepository;
    private RecipeService recipeService;

    @BeforeEach
    void setUp() {
        recipesRepository = mock(RecipesRepository.class);
        currentUserService = mock(CurrentUserService.class);
        categoriesRepository = mock(CategoriesRepository.class);
        mediaRepository = mock(MediaRepository.class);
        categoryRelationsRepository = mock(CategoryRelationsRepository.class);
        when(categoryRelationsRepository.superiorIdsByCategoryId()).thenReturn(Map.of());
        recipeService = new RecipeService(recipesRepository,
                categoriesRepository, categoryRelationsRepository, mock(TagsRepository.class),
                mock(IngredientsRepository.class), mock(UnitsRepository.class),
                mediaRepository, new ReferenceResolver(), new ReferenceNameNormalizer(),
                currentUserService, new RecipeMapper(new ReferenceMapper()));
    }

    private RecipeEntity recipe(String id, String authorId, String title) {
        UserEntity author = new UserEntity();
        author.setId(authorId);
        author.setUsername("author-" + authorId);
        RecipeEntity entity = new RecipeEntity();
        entity.setId(id);
        entity.setTitle(title);
        entity.setAuthor(author);
        return entity;
    }

    private UpdateRecipeRequest titleOnly(String title) {
        return new UpdateRecipeRequest(Optional.of(title), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    /** Every criterion absent: the home-page request, which must stay legal. */
    private static RecipeSearchRequest noFilters() {
        return new RecipeSearchRequest(null, null, null, null, null, null, null, null, null);
    }

    /**
     * The single easiest thing to get wrong in this design. findAllForSummary uses IN, which does
     * not preserve order, so its rows come back in whatever order PostgreSQL likes. Mapping them
     * straight through silently discards the sort the caller asked for - and the page still has the
     * right rows and the right count, so nothing looks broken until someone notices the
     * "newest first" list is not.
     */
    @Test
    void reordersLoadedRecipesToMatchThePageOfIds() {
        Pageable pageable = PageRequest.of(0, 20);
        List<String> pageOfIds = List.of("r-1", "r-2", "r-3");
        when(recipesRepository.searchIds(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(pageOfIds, pageable, 3));
        // Deliberately shuffled, the way a database is free to return them.
        when(recipesRepository.findAllForSummary(pageOfIds)).thenReturn(List.of(
                recipe("r-3", "u-1", "Third"),
                recipe("r-1", "u-1", "First"),
                recipe("r-2", "u-1", "Second")));

        Page<RecipeSummaryResponse> page = recipeService.search(noFilters(), pageable);

        assertThat(page.getContent()).extracting(RecipeSummaryResponse::id)
                .containsExactly("r-1", "r-2", "r-3");
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    void treatsBlankFiltersAsNoFilter_ratherThanAsAnEmptyStringToMatch() {
        // A form that submits its untouched fields sends ?title=, and that must not silently
        // become a filter matching nothing.
        Pageable pageable = PageRequest.of(0, 20);
        when(recipesRepository.searchIds(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Page.empty(pageable));

        recipeService.search(new RecipeSearchRequest("  ", "", null, "   ", "", null, "  ", null, null),
                pageable);

        verify(recipesRepository).searchIds(eq(null), eq(null), eq(null), eq(null), eq(null),
                eq(null), eq(null), any(Pageable.class));
    }

    @Test
    void normalizesTheCategoryAndTagNameFilters_soAccentsAndCaseDoNotMatter() {
        // The columns being matched are normalized_name, so the term has to be normalized the same
        // way or typing "entrees" would never find "Entrees" stored as "Entrees" with accents.
        Pageable pageable = PageRequest.of(0, 20);
        when(recipesRepository.searchIds(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Page.empty(pageable));

        recipeService.search(
                new RecipeSearchRequest(null, null, null, null, "  \u00c9NTREES ", null, "Rapide", null, null),
                pageable);

        verify(recipesRepository).searchIds(eq(null), eq(null), eq(null), eq(null), eq("entrees"),
                eq(null), eq("rapide"), any(Pageable.class));
    }

    @Test
    void takesTheSortFromTheRequestRatherThanFromTheIncomingPageable() {
        // The listing pages over a hand-written JPQL projection, and Spring appends whatever
        // property a Pageable names straight into it - so ?sort=bogus would be a 500 and
        // ?sort=author.password would order by a column no response exposes.
        Pageable pageable = PageRequest.of(2, 5, Sort.by("somethingElse"));
        when(recipesRepository.searchIds(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Page.empty(pageable));

        recipeService.search(new RecipeSearchRequest(null, null, null, null, null, null, null,
                RecipeSortField.TITLE, Sort.Direction.ASC), pageable);

        ArgumentCaptor<Pageable> used = ArgumentCaptor.forClass(Pageable.class);
        verify(recipesRepository).searchIds(any(), any(), any(), any(), any(), any(), any(),
                used.capture());
        assertThat(used.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "title"));
        // Page number and size still come from the Pageable; only the ordering is taken over.
        assertThat(used.getValue().getPageNumber()).isEqualTo(2);
        assertThat(used.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    void defaultsToNewestFirstWhenNoOrderingIsAskedFor() {
        Pageable pageable = PageRequest.of(0, 20);
        when(recipesRepository.searchIds(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Page.empty(pageable));

        recipeService.search(noFilters(), pageable);

        ArgumentCaptor<Pageable> used = ArgumentCaptor.forClass(Pageable.class);
        verify(recipesRepository).searchIds(any(), any(), any(), any(), any(), any(), any(),
                used.capture());
        assertThat(used.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @Test
    void skipsTheSecondQueryEntirelyWhenThePageIsEmpty() {
        Pageable pageable = PageRequest.of(0, 20);
        when(recipesRepository.searchIds(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Page.empty(pageable));

        assertThat(recipeService.search(noFilters(), pageable)).isEmpty();

        verify(recipesRepository, never()).findAllForSummary(any());
    }

    @Test
    void reportsAnUnknownRecipeAsNotFound() {
        when(recipesRepository.findById("ghost")).thenReturn(Optional.empty());
        when(recipesRepository.findAuthorIdById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> recipeService.findById("ghost"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> recipeService.update("ghost", titleOnly("New")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> recipeService.delete("ghost"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void letsTheAuthorEditTheirOwnRecipe() {
        RecipeEntity existing = recipe("r-1", "u-1", "Tarte");
        when(recipesRepository.findById("r-1")).thenReturn(Optional.of(existing));
        when(recipesRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(currentUserService.currentUserId()).thenReturn(Optional.of("u-1"));

        recipeService.update("r-1", titleOnly("Tarte aux pommes"));

        assertThat(existing.getTitle()).isEqualTo("Tarte aux pommes");
    }

    @Test
    void refusesToLetANonAuthorEditARecipe() {
        // The rule a URL matcher cannot express: it depends on a row in the database. Keeping it in
        // the service means a second caller reaching the same operation cannot bypass it.
        RecipeEntity existing = recipe("r-1", "u-1", "Tarte");
        when(recipesRepository.findById("r-1")).thenReturn(Optional.of(existing));
        when(currentUserService.currentUserId()).thenReturn(Optional.of("someone-else"));
        when(currentUserService.isAdmin()).thenReturn(false);

        assertThatThrownBy(() -> recipeService.update("r-1", titleOnly("Hijacked")))
                .isInstanceOf(ForbiddenOperationException.class);

        assertThat(existing.getTitle()).isEqualTo("Tarte");
        verify(recipesRepository, never()).save(any());
    }

    @Test
    void letsAnAdminEditSomeoneElsesRecipe() {
        RecipeEntity existing = recipe("r-1", "u-1", "Tarte");
        when(recipesRepository.findById("r-1")).thenReturn(Optional.of(existing));
        when(recipesRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(currentUserService.currentUserId()).thenReturn(Optional.of("an-admin"));
        when(currentUserService.isAdmin()).thenReturn(true);

        recipeService.update("r-1", titleOnly("Tidied up"));

        assertThat(existing.getTitle()).isEqualTo("Tidied up");
    }

    @Test
    void refusesToEditWhenThereIsNoAuthenticatedUserAtAll() {
        when(recipesRepository.findById("r-1")).thenReturn(Optional.of(recipe("r-1", "u-1", "Tarte")));
        when(currentUserService.currentUserId()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> recipeService.update("r-1", titleOnly("Anonymous edit")))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void deletesWithoutLoadingTheRecipe() {
        // findAuthorIdById checks permission and existence in one scalar query; DELETE has no need
        // to materialise a recipe that is about to be thrown away.
        when(recipesRepository.findAuthorIdById("r-1")).thenReturn(Optional.of("u-1"));
        when(currentUserService.currentUserId()).thenReturn(Optional.of("u-1"));

        recipeService.delete("r-1");

        verify(recipesRepository).deleteById("r-1");
        verify(recipesRepository, never()).findById(anyString());
    }

    @Test
    void refusesToLetANonAuthorDeleteARecipe() {
        when(recipesRepository.findAuthorIdById("r-1")).thenReturn(Optional.of("u-1"));
        when(currentUserService.currentUserId()).thenReturn(Optional.of("someone-else"));
        when(currentUserService.isAdmin()).thenReturn(false);

        assertThatThrownBy(() -> recipeService.delete("r-1"))
                .isInstanceOf(ForbiddenOperationException.class);

        verify(recipesRepository, never()).deleteById(anyString());
    }

    /**
     * Covers the picture paths, which the HTTP-level test does not reach without first uploading
     * real media. What matters here is the renumbering: position is never taken from the request,
     * it is derived from the order of the incoming arrays, so the array order stays the contract.
     */
    @Test
    void renumbersPicturePositionsFromTheOrderOfTheIncomingArrays() {
        CategoryEntity category = new CategoryEntity();
        category.setId("cat-1");
        category.setName("Dessert");
        when(categoriesRepository.findAllById(any())).thenReturn(List.of(category));

        MediaEntity first = media("media-1");
        MediaEntity second = media("media-2");
        MediaEntity third = media("media-3");
        when(mediaRepository.findAllById(any())).thenAnswer(invocation -> {
            Collection<String> ids = invocation.getArgument(0);
            return Stream.of(first, second, third)
                    .filter(entity -> ids.contains(entity.getId()))
                    .toList();
        });

        UserEntity author = new UserEntity();
        author.setId("u-1");
        author.setUsername("jane");
        when(currentUserService.currentUserId()).thenReturn(Optional.of("u-1"));
        when(recipesRepository.save(any())).thenAnswer(invocation -> {
            RecipeEntity saved = invocation.getArgument(0);
            // Auditing would normally stamp these; nothing is running here to do it.
            saved.setId("r-1");
            saved.setAuthor(author);
            return saved;
        });

        CreateRecipeRequest request = new CreateRecipeRequest("Tarte", null, null,
                List.of("cat-1"), List.of(),
                List.of(new RecipePictureRequest("media-2", "second"),
                        new RecipePictureRequest("media-1", "first")),
                List.of(),
                List.of(new RecipeStepRequest("Melanger",
                        List.of(new RecipePictureRequest("media-3", "step picture")))));

        recipeService.create(request);

        ArgumentCaptor<RecipeEntity> saved = ArgumentCaptor.forClass(RecipeEntity.class);
        verify(recipesRepository).save(saved.capture());
        RecipeEntity recipe = saved.getValue();

        // Sent second-then-first, so that is the order and the positions follow it - not the ids.
        assertThat(recipe.getCoverPictures())
                .extracting(cover -> cover.getMedia().getId() + "@" + cover.getPosition())
                .containsExactly("media-2@0", "media-1@1");
        assertThat(recipe.getSteps()).singleElement().satisfies(step -> {
            assertThat(step.getPosition()).isZero();
            assertThat(step.getPictures()).singleElement().satisfies(picture -> {
                assertThat(picture.getMedia().getId()).isEqualTo("media-3");
                assertThat(picture.getPosition()).isZero();
            });
        });
    }

    @Test
    void rejectsAPictureReferencingMediaThatDoesNotExist() {
        CategoryEntity category = new CategoryEntity();
        category.setId("cat-1");
        category.setName("Dessert");
        when(categoriesRepository.findAllById(any())).thenReturn(List.of(category));
        when(mediaRepository.findAllById(any())).thenReturn(List.of());
        when(currentUserService.currentUserId()).thenReturn(Optional.of("u-1"));

        CreateRecipeRequest request = new CreateRecipeRequest("Tarte", null, null,
                List.of("cat-1"), List.of(),
                List.of(new RecipePictureRequest("ghost", null)), List.of(), List.of());

        assertThatThrownBy(() -> recipeService.create(request))
                .isInstanceOf(InvalidReferenceException.class)
                .hasMessageContaining("coverPictures")
                .hasMessageContaining("ghost");

        verify(recipesRepository, never()).save(any());
    }

    /**
     * Without this, auditing resolves no author for a request with no security context, and
     * recipes.author_id is NOT NULL - so "you are not signed in" would surface as an opaque 500
     * from a constraint violation.
     */
    @Test
    void refusesToCreateARecipeWithNoAuthenticatedCaller() {
        when(currentUserService.currentUserId()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> recipeService.create(new CreateRecipeRequest("Tarte", null, null,
                List.of("cat-1"), List.of(), List.of(), List.of(), List.of())))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("signed in");

        verify(recipesRepository, never()).save(any());
    }

    @Test
    void storesAnIntroductionWhenOneIsGiven_andNoneWhenItIsBlank() {
        when(currentUserService.currentUserId()).thenReturn(Optional.of("u-1"));
        when(categoriesRepository.findAllById(any())).thenReturn(List.of(category("cat-1")));
        when(recipesRepository.save(any())).thenAnswer(invocation -> stamped(invocation.getArgument(0)));

        RecipeResponse withIntro = recipeService.create(new CreateRecipeRequest("Tarte",
                new RecipeIntroductionRequest("Une tarte de famille."), null,
                List.of("cat-1"), List.of(), List.of(), List.of(), List.of()));
        assertThat(withIntro.introduction()).isNotNull();
        assertThat(withIntro.introduction().text()).isEqualTo("Une tarte de famille.");

        RecipeResponse blank = recipeService.create(new CreateRecipeRequest("Tarte",
                new RecipeIntroductionRequest("   "), null,
                List.of("cat-1"), List.of(), List.of(), List.of(), List.of()));
        // Null, not an object with empty text: a client renders the block if the field is there.
        assertThat(blank.introduction()).isNull();
    }

    @Test
    void removesTheIntroductionWhenAnUpdateSendsBlankText() {
        // The only spelling that can mean "delete": Jackson maps both a missing property and an
        // explicit null to the same Optional.empty(), which has to keep meaning "leave it alone".
        RecipeEntity existing = recipe("r-1", "u-1", "Tarte");
        ilenreste.unpeu.recettesback.entities.recipes.RecipeIntroductionEntity introduction =
                new ilenreste.unpeu.recettesback.entities.recipes.RecipeIntroductionEntity();
        introduction.setRecipe(existing);
        introduction.setText("Une tarte de famille.");
        existing.setIntroduction(introduction);
        when(recipesRepository.findById("r-1")).thenReturn(Optional.of(existing));
        when(recipesRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(currentUserService.currentUserId()).thenReturn(Optional.of("u-1"));

        recipeService.update("r-1", new UpdateRecipeRequest(Optional.empty(),
                Optional.of(new RecipeIntroductionRequest("")), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));

        // Set to null so orphanRemoval turns it into a DELETE, rather than left as an empty block.
        assertThat(existing.getIntroduction()).isNull();
    }

    @Test
    void leavesTheIntroductionAloneWhenAnUpdateDoesNotMentionIt() {
        RecipeEntity existing = recipe("r-1", "u-1", "Tarte");
        ilenreste.unpeu.recettesback.entities.recipes.RecipeIntroductionEntity introduction =
                new ilenreste.unpeu.recettesback.entities.recipes.RecipeIntroductionEntity();
        introduction.setRecipe(existing);
        introduction.setText("Une tarte de famille.");
        existing.setIntroduction(introduction);
        when(recipesRepository.findById("r-1")).thenReturn(Optional.of(existing));
        when(recipesRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(currentUserService.currentUserId()).thenReturn(Optional.of("u-1"));

        recipeService.update("r-1", titleOnly("Tarte aux pommes"));

        assertThat(existing.getIntroduction()).isNotNull();
    }

    private CategoryEntity category(String id) {
        CategoryEntity category = new CategoryEntity();
        category.setId(id);
        category.setName("Dessert");
        return category;
    }

    /** Stands in for the auditing listener, which is not running in a unit test. */
    private RecipeEntity stamped(RecipeEntity recipe) {
        UserEntity author = new UserEntity();
        author.setId("u-1");
        author.setUsername("jane");
        recipe.setId("r-1");
        recipe.setAuthor(author);
        return recipe;
    }

    private MediaEntity media(String id) {
        MediaEntity entity = new MediaEntity();
        entity.setId(id);
        return entity;
    }
}
