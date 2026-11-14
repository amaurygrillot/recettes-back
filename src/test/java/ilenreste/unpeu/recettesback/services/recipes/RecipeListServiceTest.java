package ilenreste.unpeu.recettesback.services.recipes;

import ilenreste.unpeu.recettesback.entities.recipes.RecipeListEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeListEntryId;
import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.exceptions.InvalidInputException;
import ilenreste.unpeu.recettesback.exceptions.RecipeListConflict;
import ilenreste.unpeu.recettesback.exceptions.RecipeListConflictException;
import ilenreste.unpeu.recettesback.exceptions.ResourceNotFoundException;
import ilenreste.unpeu.recettesback.mappers.recipes.RecipeListMapper;
import ilenreste.unpeu.recettesback.models.recipes.requests.RecipeListRequest;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeListResponse;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipeListEntriesRepository;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipeListEntriesRepository.EntryCount;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipeListEntriesRepository.ListCover;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipeListsRepository;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
import ilenreste.unpeu.recettesback.services.reference.ReferenceNameNormalizer;
import ilenreste.unpeu.recettesback.services.media.MediaService;
import ilenreste.unpeu.recettesback.services.users.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The rules of the service with the repositories mocked: who may do what, the two limits at their exact
 * boundaries, idempotence, and the order in which things are checked. What the SQL does is the repository
 * tests' business, and what the statuses are is the integration test's.
 */
class RecipeListServiceTest {

    private static final String ME = "user-me";
    private static final String SOMEONE_ELSE = "user-else";
    private static final String CAKE = "\uD83C\uDF82";
    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");

    private RecipeListsRepository listsRepository;
    private RecipeListEntriesRepository entriesRepository;
    private RecipesRepository recipesRepository;
    private UsersRepository usersRepository;
    private CurrentUserService currentUserService;
    private MediaService mediaService;
    private RecipeListService service;

    @BeforeEach
    void setUp() {
        listsRepository = mock(RecipeListsRepository.class);
        entriesRepository = mock(RecipeListEntriesRepository.class);
        recipesRepository = mock(RecipesRepository.class);
        usersRepository = mock(UsersRepository.class);
        currentUserService = mock(CurrentUserService.class);
        mediaService = mock(MediaService.class);
        service = new RecipeListService(listsRepository, entriesRepository, recipesRepository, usersRepository,
                currentUserService, new ReferenceNameNormalizer(), new RecipeListMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), mediaService);
        signInAs(ME);
        when(listsRepository.saveAndFlush(any(RecipeListEntity.class))).thenAnswer(call -> call.getArgument(0));
    }

    private void signInAs(String userId) {
        when(currentUserService.currentUserId()).thenReturn(Optional.of(userId));
    }

    private static UserEntity user(String id) {
        UserEntity user = new UserEntity();
        user.setId(id);
        return user;
    }

    private RecipeListEntity storedList(String id, String ownerId, String name) {
        RecipeListEntity list = new RecipeListEntity();
        list.setId(id);
        list.setOwner(user(ownerId));
        list.setName(name);
        list.setNormalizedName(new ReferenceNameNormalizer().normalize(name));
        list.setEmoji(CAKE);
        when(listsRepository.findById(id)).thenReturn(Optional.of(list));
        when(listsRepository.findWithLockById(id)).thenReturn(Optional.of(list));
        return list;
    }

    private static RecipeListRequest request(String name) {
        return new RecipeListRequest(name, CAKE);
    }

    // ------------------------------------------------------------------------------------------------ create

    @Test
    void create_storesTheTrimmedNameItsNormalisedFormAndTheEmoji_forTheCaller() {
        when(usersRepository.getReferenceById(ME)).thenReturn(user(ME));

        RecipeListResponse created = service.create(new RecipeListRequest("  Pour le Dimanche  ", "\uD83C\uDF5D"));

        verify(listsRepository).saveAndFlush(any(RecipeListEntity.class));
        assertThat(created.name()).isEqualTo("Pour le Dimanche");
        assertThat(created.emoji()).isEqualTo("\uD83C\uDF5D");
        assertThat(created.recipeCount()).isZero();
        assertThat(created.recipeCoverMediaIds()).isEmpty();
        assertThat(created.containsRecipe()).as("only GET /lists?containing= sets it").isNull();
    }

    @Test
    void create_recordsTheOwnerFromTheTokenNeverFromTheBody() {
        UserEntity me = user(ME);
        when(usersRepository.getReferenceById(ME)).thenReturn(me);

        service.create(request("Desserts"));

        verify(listsRepository).saveAndFlush(org.mockito.ArgumentMatchers.argThat(list ->
                list.getOwner() == me && list.getNormalizedName().equals("desserts")));
    }

    @Test
    void create_refusesTheFiftyFirstList_withTheTooManyListsConflict_andWritesNothing() {
        when(listsRepository.countByOwnerId(ME)).thenReturn((long) RecipeListService.MAX_LISTS_PER_MEMBER);

        assertThatThrownBy(() -> service.create(request("Desserts")))
                .isInstanceOfSatisfying(RecipeListConflictException.class,
                        exception -> assertThat(exception.conflict()).isEqualTo(RecipeListConflict.TOO_MANY_LISTS));

        verify(listsRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_acceptsTheFiftiethList() {
        when(listsRepository.countByOwnerId(ME)).thenReturn(RecipeListService.MAX_LISTS_PER_MEMBER - 1L);
        when(usersRepository.getReferenceById(ME)).thenReturn(user(ME));

        assertThat(service.create(request("La derniere")).name()).isEqualTo("La derniere");
    }

    @Test
    void create_refusesANameTheCallerAlreadyUses_evenWrittenWithOtherAccentsAndCase() {
        RecipeListEntity existing = storedList("existing", ME, "Desserts");
        when(listsRepository.findByOwnerIdAndNormalizedName(ME, "desserts")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(request("  D\u00C9SSERTS ")))
                .isInstanceOfSatisfying(RecipeListConflictException.class,
                        exception -> assertThat(exception.conflict()).isEqualTo(RecipeListConflict.NAME_TAKEN));

        verify(listsRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_asksAboutTheCallersOwnLists_soAnotherMembersNameIsNeverAConflict() {
        when(usersRepository.getReferenceById(ME)).thenReturn(user(ME));
        RecipeListEntity theirs = storedList("theirs", SOMEONE_ELSE, "Desserts");
        when(listsRepository.findByOwnerIdAndNormalizedName(SOMEONE_ELSE, "desserts"))
                .thenReturn(Optional.of(theirs));

        assertThat(service.create(request("Desserts")).name()).isEqualTo("Desserts");
    }

    @Test
    void create_whenNobodyIsSignedIn_isAnAuthenticationFailure_notAServerError() {
        when(currentUserService.currentUserId()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request("Desserts")))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        verifyNoInteractions(listsRepository);
    }

    // ------------------------------------------------------------------------------------------------ update

    @Test
    void update_replacesTheNameAndTheEmoji() {
        storedList("list-1", ME, "Desserts");

        RecipeListResponse updated = service.update("list-1", new RecipeListRequest("Gouters", "\uD83C\uDF5D"));

        assertThat(updated.name()).isEqualTo("Gouters");
        assertThat(updated.emoji()).isEqualTo("\uD83C\uDF5D");
    }

    @Test
    void update_mayKeepItsOwnNormalisedName_whichIsHowACaseOrAccentFixWorks() {
        RecipeListEntity own = storedList("list-1", ME, "desserts");
        when(listsRepository.findByOwnerIdAndNormalizedName(ME, "desserts")).thenReturn(Optional.of(own));

        assertThat(service.update("list-1", request("D\u00E9sserts")).name()).isEqualTo("D\u00E9sserts");
    }

    @Test
    void update_refusesTheNameOfAnotherListOfTheCaller() {
        storedList("list-1", ME, "Desserts");
        RecipeListEntity other = storedList("list-2", ME, "Gouters");
        when(listsRepository.findByOwnerIdAndNormalizedName(ME, "gouters")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.update("list-1", request("gouters")))
                .isInstanceOfSatisfying(RecipeListConflictException.class,
                        exception -> assertThat(exception.conflict()).isEqualTo(RecipeListConflict.NAME_TAKEN));
        verify(listsRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_ofSomeoneElsesList_isIndistinguishableFromAListThatDoesNotExist() {
        storedList("theirs", SOMEONE_ELSE, "Desserts");

        Throwable notYours = org.assertj.core.api.Assertions.catchThrowable(() -> service.update("theirs", request("X")));
        Throwable nonexistent = org.assertj.core.api.Assertions.catchThrowable(() -> service.update("nope", request("X")));

        assertThat(notYours).isInstanceOf(ResourceNotFoundException.class);
        assertThat(nonexistent).isInstanceOf(ResourceNotFoundException.class);
        assertThat(notYours.getMessage()).isEqualTo("No recipe list with id theirs");
        assertThat(nonexistent.getMessage()).isEqualTo("No recipe list with id nope");
        verify(listsRepository, never()).saveAndFlush(any());
    }

    // ---------------------------------------------------------------------------------------------- get / read

    @Test
    void get_returnsTheCountAndTheMosaicOfTheList() {
        storedList("list-1", ME, "Desserts");
        EntryCount twelve = entryCount("list-1", 12);
        ListCover first = cover("list-1", "m1");
        ListCover second = cover("list-1", "m2");
        when(entriesRepository.countEntriesOf(List.of("list-1"))).thenReturn(List.of(twelve));
        when(entriesRepository.findCovers(List.of("list-1"), RecipeListService.COVER_MOSAIC_SIZE))
                .thenReturn(List.of(first, second));

        RecipeListResponse response = service.get("list-1");

        assertThat(response.recipeCount()).isEqualTo(12);
        assertThat(response.recipeCoverMediaIds()).containsExactly("m1", "m2");
        assertThat(response.containsRecipe()).isNull();
    }

    @Test
    void get_ofSomeoneElsesList_is404() {
        storedList("theirs", SOMEONE_ELSE, "Desserts");

        assertThatThrownBy(() -> service.get("theirs")).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(entriesRepository);
    }

    @Test
    void requireVisible_passesForTheOwnersList_andIs404ForAnyoneElses() {
        storedList("mine", ME, "Desserts");
        storedList("theirs", SOMEONE_ELSE, "Desserts");

        service.requireVisible("mine");
        assertThatThrownBy(() -> service.requireVisible("theirs")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.requireVisible("nope")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void requireVisible_whenNobodyIsSignedIn_isAnAuthenticationFailure_beforeAnyLookup() {
        when(currentUserService.currentUserId()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireVisible("mine"))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        verifyNoInteractions(listsRepository);
    }

    @Test
    void list_withNoLists_runsNoAggregateQueryAtAll() {
        when(listsRepository.findByOwnerIdOrderByNormalizedNameAscNameAsc(ME)).thenReturn(List.of());

        assertThat(service.list(null)).isEmpty();
        verifyNoInteractions(entriesRepository);
    }

    @Test
    void list_countsAndCoversEveryListInOneQueryEach_andDefaultsToZeroAndNoCovers() {
        RecipeListEntity full = storedList("full", ME, "Pleine");
        RecipeListEntity empty = storedList("empty", ME, "Vide");
        when(listsRepository.findByOwnerIdOrderByNormalizedNameAscNameAsc(ME)).thenReturn(List.of(full, empty));
        List<String> ids = List.of("full", "empty");
        EntryCount three = entryCount("full", 3);
        ListCover onlyCover = cover("full", "m1");
        when(entriesRepository.countEntriesOf(ids)).thenReturn(List.of(three));
        when(entriesRepository.findCovers(ids, RecipeListService.COVER_MOSAIC_SIZE)).thenReturn(List.of(onlyCover));

        List<RecipeListResponse> lists = service.list(null);

        assertThat(lists).extracting(RecipeListResponse::name).containsExactly("Pleine", "Vide");
        assertThat(lists.get(0).recipeCount()).isEqualTo(3);
        assertThat(lists.get(0).recipeCoverMediaIds()).containsExactly("m1");
        assertThat(lists.get(1).recipeCount()).isZero();
        assertThat(lists.get(1).recipeCoverMediaIds()).isEmpty();
        assertThat(lists).extracting(RecipeListResponse::containsRecipe).containsOnlyNulls();
        verify(entriesRepository).countEntriesOf(anyCollection());
        verify(entriesRepository).findCovers(anyCollection(), anyInt());
        verify(recipesRepository, never()).existsById(anyString());
    }

    @Test
    void list_withContaining_saysForEachListWhetherItHoldsTheRecipe() {
        RecipeListEntity holding = storedList("holding", ME, "Avec");
        RecipeListEntity without = storedList("without", ME, "Sans");
        when(listsRepository.findByOwnerIdOrderByNormalizedNameAscNameAsc(ME)).thenReturn(List.of(holding, without));
        when(recipesRepository.existsById("tarte")).thenReturn(true);
        when(entriesRepository.findListIdsHolding("tarte", ME)).thenReturn(Set.of("holding"));

        List<RecipeListResponse> lists = service.list("tarte");

        assertThat(lists).extracting(RecipeListResponse::containsRecipe).containsExactly(true, false);
    }

    @Test
    void list_withContainingNamingNoRecipe_is404() {
        when(recipesRepository.existsById("nope")).thenReturn(false);

        assertThatThrownBy(() -> service.list("nope")).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(listsRepository);
    }

    @Test
    void list_withABlankContaining_isAsIfItWereAbsent() {
        RecipeListEntity one = storedList("one", ME, "Une");
        when(listsRepository.findByOwnerIdOrderByNormalizedNameAscNameAsc(ME)).thenReturn(List.of(one));

        assertThat(service.list("  ")).extracting(RecipeListResponse::containsRecipe).containsOnlyNulls();
        verifyNoInteractions(recipesRepository);
    }

    @Test
    void list_onlyEverAsksAboutTheCallersOwnLists() {
        signInAs(SOMEONE_ELSE);

        service.list(null);

        verify(listsRepository).findByOwnerIdOrderByNormalizedNameAscNameAsc(SOMEONE_ELSE);
        verify(listsRepository, never()).findByOwnerIdOrderByNormalizedNameAscNameAsc(ME);
    }

    // ------------------------------------------------------------------------------------------------ delete

    @Test
    void delete_removesTheCallersList() {
        RecipeListEntity mine = storedList("mine", ME, "Desserts");

        service.delete("mine");

        verify(listsRepository).delete(mine);
    }

    @Test
    void delete_ofSomeoneElsesList_is404_andDeletesNothing() {
        storedList("theirs", SOMEONE_ELSE, "Desserts");

        assertThatThrownBy(() -> service.delete("theirs")).isInstanceOf(ResourceNotFoundException.class);

        verify(listsRepository, never()).delete(any());
    }

    // --------------------------------------------------------------------------------------------- addRecipe

    @Test
    void addRecipe_putsTheRecipeInTheListStampedWithTheClock() {
        storedList("list-1", ME, "Desserts");
        when(recipesRepository.existsById("tarte")).thenReturn(true);
        when(entriesRepository.insertIfAbsent("list-1", "tarte", NOW)).thenReturn(1);

        service.addRecipe("list-1", "tarte");

        verify(entriesRepository).insertIfAbsent("list-1", "tarte", NOW);
    }

    @Test
    void addRecipe_thatIsAlreadyThere_isASuccessThatChangesNothing() {
        storedList("list-1", ME, "Desserts");
        when(recipesRepository.existsById("tarte")).thenReturn(true);
        when(entriesRepository.insertIfAbsent("list-1", "tarte", NOW)).thenReturn(0);

        service.addRecipe("list-1", "tarte");

        verify(entriesRepository).insertIfAbsent("list-1", "tarte", NOW);
    }

    @Test
    void addRecipe_toAFullList_isTheListFullConflict() {
        storedList("list-1", ME, "Desserts");
        when(recipesRepository.existsById("tarte")).thenReturn(true);
        when(entriesRepository.countByListId("list-1")).thenReturn((long) RecipeListService.MAX_RECIPES_PER_LIST);

        assertThatThrownBy(() -> service.addRecipe("list-1", "tarte"))
                .isInstanceOfSatisfying(RecipeListConflictException.class,
                        exception -> assertThat(exception.conflict()).isEqualTo(RecipeListConflict.LIST_FULL));

        verify(entriesRepository, never()).insertIfAbsent(anyString(), anyString(), any());
    }

    @Test
    void addRecipe_toAListHoldingTwoHundredLessOne_isAccepted() {
        storedList("list-1", ME, "Desserts");
        when(recipesRepository.existsById("tarte")).thenReturn(true);
        when(entriesRepository.countByListId("list-1")).thenReturn(RecipeListService.MAX_RECIPES_PER_LIST - 1L);
        when(entriesRepository.insertIfAbsent("list-1", "tarte", NOW)).thenReturn(1);

        service.addRecipe("list-1", "tarte");

        verify(entriesRepository).insertIfAbsent("list-1", "tarte", NOW);
    }

    @Test
    void addRecipe_thatIsAlreadyInAFullList_stillSucceeds_becauseNothingIsBeingAdded() {
        storedList("list-1", ME, "Desserts");
        when(recipesRepository.existsById("tarte")).thenReturn(true);
        when(entriesRepository.existsById(new RecipeListEntryId("list-1", "tarte"))).thenReturn(true);
        when(entriesRepository.countByListId("list-1")).thenReturn((long) RecipeListService.MAX_RECIPES_PER_LIST);
        when(entriesRepository.insertIfAbsent("list-1", "tarte", NOW)).thenReturn(0);

        service.addRecipe("list-1", "tarte");

        verify(entriesRepository).insertIfAbsent("list-1", "tarte", NOW);
    }

    @Test
    void addRecipe_unknownRecipe_is404_andNothingIsInserted() {
        storedList("list-1", ME, "Desserts");
        when(recipesRepository.existsById("nope")).thenReturn(false);

        assertThatThrownBy(() -> service.addRecipe("list-1", "nope")).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("No recipe with id nope");

        verifyNoInteractions(entriesRepository);
    }

    /** A request about someone else's list must not reveal anything about the recipe. */
    @Test
    void addRecipe_toSomeoneElsesList_is404BeforeTheRecipeIsEvenLookedUp() {
        storedList("theirs", SOMEONE_ELSE, "Desserts");

        assertThatThrownBy(() -> service.addRecipe("theirs", "tarte")).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("No recipe list with id theirs");

        verifyNoInteractions(recipesRepository);
        verifyNoInteractions(entriesRepository);
    }

    // ------------------------------------------------------------------------------------------ removeRecipe

    @Test
    void removeRecipe_takesItOutOfTheList() {
        storedList("list-1", ME, "Desserts");
        when(entriesRepository.deleteEntry("list-1", "tarte")).thenReturn(1);

        service.removeRecipe("list-1", "tarte");

        verify(entriesRepository).deleteEntry("list-1", "tarte");
    }

    @Test
    void removeRecipe_thatIsNotInTheList_isASuccess_withoutCheckingThatTheRecipeExists() {
        storedList("list-1", ME, "Desserts");
        when(entriesRepository.deleteEntry("list-1", "gone")).thenReturn(0);

        service.removeRecipe("list-1", "gone");

        verifyNoInteractions(recipesRepository);
    }

    @Test
    void removeRecipe_fromSomeoneElsesList_is404_andRemovesNothing() {
        storedList("theirs", SOMEONE_ELSE, "Desserts");

        assertThatThrownBy(() -> service.removeRecipe("theirs", "tarte")).isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(entriesRepository);
    }

    // ------------------------------------------------------------------- a name with nothing to see in it

    private static String ofCodePoints(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    /**
     * Bean validation rejects what String.isBlank() calls blank, and no more. These pass it, and a list called
     * any of them shows an empty tile — the last one even normalises to the empty string, so the second such list
     * would be refused as a duplicate of the first.
     */
    @ParameterizedTest(name = "a name made of {0}")
    @MethodSource("namesWithNothingToSee")
    void aNameWithNothingVisibleInIt_isRefused_beforeAnythingIsLookedUpOrSaved(String description, String name) {
        assertThatThrownBy(() -> service.create(request(name))).isInstanceOf(InvalidInputException.class);

        storedList("list-1", ME, "Desserts");
        assertThatThrownBy(() -> service.update("list-1", request(name))).isInstanceOf(InvalidInputException.class);

        verify(listsRepository, never()).saveAndFlush(any());
        verify(listsRepository, never()).findByOwnerIdAndNormalizedName(anyString(), anyString());
    }

    static Stream<Arguments> namesWithNothingToSee() {
        return Stream.of(
                Arguments.of("a no-break space", ofCodePoints(0x00A0)),
                Arguments.of("a zero-width space", ofCodePoints(0x200B)),
                Arguments.of("a lone combining accent", ofCodePoints(0x0301)),
                Arguments.of("an ideographic space", ofCodePoints(0x3000)),
                Arguments.of("a soft hyphen", ofCodePoints(0x00AD)),
                Arguments.of("a Hangul filler", ofCodePoints(0x3164)),
                Arguments.of("a blank Braille pattern", ofCodePoints(0x2800)),
                Arguments.of("spaces around a combining accent", "  " + ofCodePoints(0x0301) + " "));
    }

    /** PostgreSQL refuses 0x00 in text: without this, a perfectly valid JSON string would be a 500. */
    @ParameterizedTest(name = "a name containing {0}")
    @MethodSource("namesWithControlCharacters")
    void aNameWithAControlCharacterAnywhereInIt_isRefused_evenNextToVisibleText(String description, String name) {
        assertThatThrownBy(() -> service.create(request(name))).isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("control");

        verify(listsRepository, never()).saveAndFlush(any());
    }

    /** A control character at either END is trimmed with the spaces, as String.trim() does: "Tartes" plus a newline is fine. */
    @Test
    void aControlCharacterAtTheEdgeOfAName_isTrimmedAwayWithTheSpaces() {
        when(usersRepository.getReferenceById(ME)).thenReturn(user(ME));

        assertThat(service.create(request(ofCodePoints(0x000A) + "Tartes" + ofCodePoints(0x0009))).name())
                .isEqualTo("Tartes");
    }

    static Stream<Arguments> namesWithControlCharacters() {
        return Stream.of(
                Arguments.of("a NUL", "Ta" + ofCodePoints(0x0000) + "rtes"),
                Arguments.of("a line break", "Tartes" + ofCodePoints(0x000A) + "salees"),
                Arguments.of("a tab", "Tartes" + ofCodePoints(0x0009) + "salees"),
                Arguments.of("an escape", "Tar" + ofCodePoints(0x001B) + "tes"),
                Arguments.of("a line separator", "Tartes" + ofCodePoints(0x2028) + "salees"),
                Arguments.of("a paragraph separator", "Tartes" + ofCodePoints(0x2029) + "salees"));
    }

    @Test
    void aNameWithAVisibleCharacterAmongTheInvisibleOnes_isAccepted() {
        when(usersRepository.getReferenceById(ME)).thenReturn(user(ME));

        assertThat(service.create(request(ofCodePoints(0x00A0) + "Tartes" + ofCodePoints(0x200B))).name())
                .contains("Tartes");
    }

    // ------------------------------------------------------------------- the deletes that race a write

    @Test
    void addRecipe_whenTheListOrTheRecipeIsDeletedBeforeTheInsert_is404_notAServerError() {
        storedList("list-1", ME, "Desserts");
        when(recipesRepository.existsById("tarte")).thenReturn(true);
        when(entriesRepository.insertIfAbsent("list-1", "tarte", NOW)).thenThrow(new DataIntegrityViolationException(
                "foreign key violation", new SQLException("insert violates foreign key constraint", "23503")));

        assertThatThrownBy(() -> service.addRecipe("list-1", "tarte")).isInstanceOf(ResourceNotFoundException.class);
    }

    /** Only a foreign key is taken for "it was deleted": any other refusal is a real, unexpected failure. */
    @Test
    void addRecipe_whenSomeOtherConstraintRefusesTheInsert_isNotTurnedInto404() {
        storedList("list-1", ME, "Desserts");
        when(recipesRepository.existsById("tarte")).thenReturn(true);
        DataIntegrityViolationException notNull = new DataIntegrityViolationException(
                "null value", new SQLException("null value in column violates not-null constraint", "23502"));
        when(entriesRepository.insertIfAbsent("list-1", "tarte", NOW)).thenThrow(notNull);

        assertThatThrownBy(() -> service.addRecipe("list-1", "tarte")).isSameAs(notNull);
    }

    @Test
    void update_whenTheListIsDeletedBeforeTheWrite_is404_notAServerError() {
        storedList("list-1", ME, "Desserts");
        when(listsRepository.saveAndFlush(any(RecipeListEntity.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(RecipeListEntity.class, "list-1"));

        assertThatThrownBy(() -> service.update("list-1", request("Gouters")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("No recipe list with id list-1");
    }

    // ------------------------------------------------------------------- fewer round trips

    @Test
    void create_doesNotAskTheDatabaseToCountOrFindCoversForAListThatIsKnownToBeEmpty() {
        when(usersRepository.getReferenceById(ME)).thenReturn(user(ME));

        service.create(request("Desserts"));

        verifyNoInteractions(entriesRepository);
    }

    @Test
    void addRecipe_asksWhetherTheRecipeIsAlreadyThere_onlyWhenTheListIsFull() {
        storedList("list-1", ME, "Desserts");
        when(recipesRepository.existsById("tarte")).thenReturn(true);
        when(entriesRepository.countByListId("list-1")).thenReturn(RecipeListService.MAX_RECIPES_PER_LIST - 1L);
        when(entriesRepository.insertIfAbsent("list-1", "tarte", NOW)).thenReturn(1);

        service.addRecipe("list-1", "tarte");

        verify(entriesRepository, never()).existsById(any(RecipeListEntryId.class));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static EntryCount entryCount(String listId, long count) {
        EntryCount entryCount = mock(EntryCount.class);
        when(entryCount.getListId()).thenReturn(listId);
        when(entryCount.getRecipeCount()).thenReturn(count);
        return entryCount;
    }

    private static ListCover cover(String listId, String mediaId) {
        ListCover cover = mock(ListCover.class);
        when(cover.getListId()).thenReturn(listId);
        when(cover.getMediaId()).thenReturn(mediaId);
        return cover;
    }

    @Test
    void theLimitsAreTheOnesTheProductDecided() {
        assertThat(RecipeListService.MAX_LISTS_PER_MEMBER).isEqualTo(50);
        assertThat(RecipeListService.MAX_RECIPES_PER_LIST).isEqualTo(200);
        assertThat(RecipeListService.COVER_MOSAIC_SIZE).isEqualTo(4);
    }
}
