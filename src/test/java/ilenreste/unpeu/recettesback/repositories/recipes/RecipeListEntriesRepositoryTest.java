package ilenreste.unpeu.recettesback.repositories.recipes;

import ilenreste.unpeu.recettesback.configuration.JpaAuditingConfig;
import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeListEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeListEntryEntity;
import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.repositories.media.MediaRepository;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipeListEntriesRepository.EntryCount;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipeListEntriesRepository.ListCover;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
import ilenreste.unpeu.recettesback.services.users.CurrentUserService;
import ilenreste.unpeu.recettesback.support.RecipeListFixtures;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every hand-written query that reads or writes the entries of a recipe list, against the real PostgreSQL.
 * <p>
 * A mocked repository proves nothing about the window function behind the cover mosaic, the
 * {@code ON CONFLICT} behind the idempotent add, or a primary key that has to refuse a duplicate; and
 * {@code @DataJpaTest} rolls back, so the development database is not left with the rows.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, CurrentUserService.class})
class RecipeListEntriesRepositoryTest {

    private static final Instant NOON = Instant.parse("2026-11-08T12:00:00Z");

    @Autowired
    private RecipeListEntriesRepository entriesRepository;
    @Autowired
    private RecipeListsRepository listsRepository;
    @Autowired
    private RecipesRepository recipesRepository;
    @Autowired
    private MediaRepository mediaRepository;
    @Autowired
    private UsersRepository usersRepository;
    @Autowired
    private EntityManager entityManager;

    private RecipeListFixtures fixtures;
    private UserEntity owner;
    private UserEntity stranger;

    @BeforeEach
    void setUp() {
        fixtures = new RecipeListFixtures(usersRepository, recipesRepository, mediaRepository, listsRepository,
                entriesRepository);
        owner = fixtures.user("owner");
        stranger = fixtures.user("stranger");
        fixtures.authenticateAs(owner);
    }

    @AfterEach
    void tearDown() {
        fixtures.signOut();
    }

    private static Instant minutesAfterNoon(int minutes) {
        return NOON.plusSeconds(60L * minutes);
    }

    private Map<String, List<String>> coversByList(List<RecipeListEntity> lists, int limit) {
        entityManager.clear();
        return entriesRepository.findCovers(lists.stream().map(RecipeListEntity::getId).toList(), limit).stream()
                .collect(Collectors.groupingBy(ListCover::getListId,
                        Collectors.mapping(ListCover::getMediaId, Collectors.toList())));
    }

    @Test
    void countsTheRecipesOfSeveralListsInOneStatement_andOmitsTheEmptyOnes() {
        RecipeListEntity two = fixtures.list(owner, "Deux");
        RecipeListEntity three = fixtures.list(owner, "Trois");
        RecipeListEntity none = fixtures.list(owner, "Aucune");
        fixtures.put(two, fixtures.recipe("A"), NOON);
        fixtures.put(two, fixtures.recipe("B"), NOON);
        RecipeEntity shared = fixtures.recipe("C");
        fixtures.put(three, shared, NOON);
        fixtures.put(three, fixtures.recipe("D"), NOON);
        fixtures.put(three, fixtures.recipe("E"), NOON);

        Map<String, Long> counts = entriesRepository
                .countEntriesOf(List.of(two.getId(), three.getId(), none.getId())).stream()
                .collect(Collectors.toMap(EntryCount::getListId, EntryCount::getRecipeCount));

        assertThat(counts).containsOnly(Map.entry(two.getId(), 2L), Map.entry(three.getId(), 3L));
        assertThat(entriesRepository.countByListId(three.getId())).isEqualTo(3);
        assertThat(entriesRepository.countByListId(none.getId())).isZero();
    }

    @Test
    void theCoverMosaicIsTheFirstPictureOfTheNewestRecipesThatHaveOne_newestFirst() {
        RecipeListEntity list = fixtures.list(owner, "Dimanche");
        MediaEntity oldest = fixtures.media();
        MediaEntity secondPicture = fixtures.media();
        MediaEntity second = fixtures.media();
        MediaEntity third = fixtures.media();
        MediaEntity fourth = fixtures.media();
        MediaEntity newest = fixtures.media();
        // Added in this order, oldest first. The one with two covers must contribute its FIRST (position 0).
        fixtures.put(list, fixtures.recipeWithCovers("1 oldest", oldest), minutesAfterNoon(1));
        fixtures.put(list, fixtures.recipeWithCovers("2", second, secondPicture), minutesAfterNoon(2));
        fixtures.put(list, fixtures.recipeWithCovers("3", third), minutesAfterNoon(3));
        fixtures.put(list, fixtures.recipeWithCovers("4", fourth), minutesAfterNoon(4));
        fixtures.put(list, fixtures.recipeWithCovers("5 newest", newest), minutesAfterNoon(5));

        List<String> mosaic = coversByList(List.of(list), 4).get(list.getId());

        assertThat(mosaic).containsExactly(newest.getId(), fourth.getId(), third.getId(), second.getId());
    }

    @Test
    void aRecipeWithoutAPictureIsSkippedRatherThanTakingASlot() {
        RecipeListEntity list = fixtures.list(owner, "Dimanche");
        MediaEntity older = fixtures.media();
        MediaEntity oldest = fixtures.media();
        fixtures.put(list, fixtures.recipeWithCovers("oldest", oldest), minutesAfterNoon(1));
        fixtures.put(list, fixtures.recipeWithCovers("older", older), minutesAfterNoon(2));
        // The three newest recipes have no photo at all.
        fixtures.put(list, fixtures.recipe("no photo 1"), minutesAfterNoon(3));
        fixtures.put(list, fixtures.recipe("no photo 2"), minutesAfterNoon(4));
        fixtures.put(list, fixtures.recipe("no photo 3"), minutesAfterNoon(5));

        assertThat(coversByList(List.of(list), 4).get(list.getId())).containsExactly(older.getId(), oldest.getId());
    }

    @Test
    void aListWhoseRecipesHaveNoPictureHasNoRowAtAll() {
        RecipeListEntity list = fixtures.list(owner, "Sans photo");
        fixtures.put(list, fixtures.recipe("plain"), NOON);

        assertThat(coversByList(List.of(list), 4)).doesNotContainKey(list.getId());
    }

    @Test
    void theMosaicOfEachListIsComputedIndependently_inOneStatement() {
        RecipeListEntity first = fixtures.list(owner, "Une");
        RecipeListEntity second = fixtures.list(owner, "Deux");
        MediaEntity sharedPicture = fixtures.media();
        MediaEntity onlyInFirst = fixtures.media();
        RecipeEntity shared = fixtures.recipeWithCovers("shared", sharedPicture);
        fixtures.put(first, shared, minutesAfterNoon(1));
        fixtures.put(first, fixtures.recipeWithCovers("first only", onlyInFirst), minutesAfterNoon(2));
        fixtures.put(second, shared, minutesAfterNoon(3));

        Map<String, List<String>> mosaics = coversByList(List.of(first, second), 4);

        assertThat(mosaics.get(first.getId())).containsExactly(onlyInFirst.getId(), sharedPicture.getId());
        assertThat(mosaics.get(second.getId())).containsExactly(sharedPicture.getId());
    }

    @Test
    void theMosaicIsCappedAtTheLimitItIsGiven() {
        RecipeListEntity list = fixtures.list(owner, "Dimanche");
        for (int minute = 1; minute <= 3; minute++) {
            fixtures.put(list, fixtures.recipeWithCovers("recipe " + minute, fixtures.media()), minutesAfterNoon(minute));
        }

        assertThat(coversByList(List.of(list), 2).get(list.getId())).hasSize(2);
    }

    @Test
    void findsWhichOfTheOwnersListsHoldARecipe_andNeverASomeoneElsesList() {
        RecipeEntity recipe = fixtures.recipe("Tarte");
        RecipeListEntity holding = fixtures.list(owner, "Avec");
        fixtures.list(owner, "Sans");
        RecipeListEntity strangersHolding = fixtures.list(stranger, "Avec aussi");
        fixtures.put(holding, recipe, NOON);
        fixtures.put(strangersHolding, recipe, NOON);

        assertThat(entriesRepository.findListIdsHolding(recipe.getId(), owner.getId()))
                .containsExactly(holding.getId());
        assertThat(entriesRepository.findListIdsHolding(recipe.getId(), stranger.getId()))
                .containsExactly(strangersHolding.getId());
    }

    @Test
    void insertingARecipeThatIsAlreadyThereChangesNothing_andSaysSo() {
        RecipeListEntity list = fixtures.list(owner, "Dimanche");
        RecipeEntity recipe = fixtures.recipe("Tarte");

        int first = entriesRepository.insertIfAbsent(list.getId(), recipe.getId(), NOON);
        int again = entriesRepository.insertIfAbsent(list.getId(), recipe.getId(), minutesAfterNoon(30));

        assertThat(first).isEqualTo(1);
        assertThat(again).isZero();
        entityManager.clear();
        List<RecipeListEntryEntity> entries = entriesRepository.findAll();
        assertThat(entries).filteredOn(entry -> entry.getList().getId().equals(list.getId()))
                .singleElement()
                .satisfies(entry -> assertThat(entry.getAddedAt()).isEqualTo(NOON));
    }

    @Test
    void removingARecipeIsIdempotent() {
        RecipeListEntity list = fixtures.list(owner, "Dimanche");
        RecipeEntity recipe = fixtures.recipe("Tarte");
        fixtures.put(list, recipe, NOON);

        assertThat(entriesRepository.deleteEntry(list.getId(), recipe.getId())).isEqualTo(1);
        assertThat(entriesRepository.deleteEntry(list.getId(), recipe.getId())).isZero();
        assertThat(entriesRepository.countByListId(list.getId())).isZero();
        assertThat(recipesRepository.existsById(recipe.getId())).as("the recipe itself stays").isTrue();
    }

    @Test
    void removingFromOneListLeavesTheSameRecipeInTheOthers() {
        RecipeEntity recipe = fixtures.recipe("Tarte");
        RecipeListEntity one = fixtures.list(owner, "Une");
        RecipeListEntity other = fixtures.list(owner, "Autre");
        fixtures.put(one, recipe, NOON);
        fixtures.put(other, recipe, NOON);

        entriesRepository.deleteEntry(one.getId(), recipe.getId());

        assertThat(entriesRepository.countByListId(one.getId())).isZero();
        assertThat(entriesRepository.countByListId(other.getId())).isEqualTo(1);
    }

    /**
     * The primary key is what makes "at most once" a fact of the schema. The statement the service uses
     * swallows the conflict; a plain insert must still hit it, and the constraint is named, not anonymous.
     */
    @Test
    void thePrimaryKeyRefusesTheSameRecipeTwiceInOneList() {
        RecipeListEntity list = fixtures.list(owner, "Dimanche");
        RecipeEntity recipe = fixtures.recipe("Tarte");
        entityManager.persist(entry(list, recipe));
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> {
            entityManager.persist(entry(listsRepository.getReferenceById(list.getId()),
                    recipesRepository.getReferenceById(recipe.getId())));
            entityManager.flush();
        }).isInstanceOfSatisfying(ConstraintViolationException.class, violation ->
                assertThat(violation.getConstraintName()).isEqualTo("recipe_list_entries_pkey"));
    }

    private static RecipeListEntryEntity entry(RecipeListEntity list, RecipeEntity recipe) {
        RecipeListEntryEntity entry = new RecipeListEntryEntity();
        entry.setList(list);
        entry.setRecipe(recipe);
        entry.setAddedAt(NOON);
        return entry;
    }

    @Test
    void theSameRecipeMayBeInManyListsAndAListMayHoldManyRecipes() {
        RecipeEntity recipe = fixtures.recipe("Tarte");
        RecipeListEntity one = fixtures.list(owner, "Une");
        RecipeListEntity other = fixtures.list(owner, "Autre");

        assertThat(entriesRepository.insertIfAbsent(one.getId(), recipe.getId(), NOON)).isEqualTo(1);
        assertThat(entriesRepository.insertIfAbsent(other.getId(), recipe.getId(), NOON)).isEqualTo(1);
        assertThat(entriesRepository.insertIfAbsent(one.getId(), fixtures.recipe("Soupe").getId(), NOON)).isEqualTo(1);
        assertThat(entriesRepository.countEntriesOf(List.of(one.getId(), other.getId())).stream()
                .collect(Collectors.toMap(EntryCount::getListId, EntryCount::getRecipeCount)))
                .containsOnly(Map.entry(one.getId(), 2L), Map.entry(other.getId(), 1L));
    }
}
