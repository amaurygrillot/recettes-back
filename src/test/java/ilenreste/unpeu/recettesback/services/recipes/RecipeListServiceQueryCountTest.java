package ilenreste.unpeu.recettesback.services.recipes;

import ilenreste.unpeu.recettesback.configuration.JpaAuditingConfig;
import ilenreste.unpeu.recettesback.configuration.TimeConfig;
import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeListEntity;
import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.mappers.recipes.RecipeListMapper;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeListResponse;
import ilenreste.unpeu.recettesback.repositories.media.MediaRepository;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipeListEntriesRepository;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipeListsRepository;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
import ilenreste.unpeu.recettesback.services.reference.ReferenceNameNormalizer;
import ilenreste.unpeu.recettesback.services.users.CurrentUserService;
import ilenreste.unpeu.recettesback.support.RecipeListFixtures;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "No N+1: this is one or a few queries whatever the number of lists."
 * <p>
 * The service runs for real, against the real PostgreSQL, over 2 lists and then over 20, and Hibernate's own
 * statistics count the statements it prepared. The two counts must be equal. An N+1 — a count or a mosaic read
 * per list, or a lazy owner loaded per list while mapping — would make the second larger.
 */
// Its own context (the statistics switch), kept at two connections: it runs on one thread, and every context a
// suite creates holds its pool open for the rest of the build. See RecipeListsHttpSupport.
@DataJpaTest(properties = {"spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.datasource.hikari.maximum-pool-size=2"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, TimeConfig.class, CurrentUserService.class, RecipeListService.class,
        RecipeListMapper.class, ReferenceNameNormalizer.class})
class RecipeListServiceQueryCountTest {

    private static final Instant NOON = Instant.parse("2026-11-08T12:00:00Z");

    @Autowired
    private RecipeListService service;
    @Autowired
    private RecipeListsRepository listsRepository;
    @Autowired
    private RecipeListEntriesRepository entriesRepository;
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
    private RecipeEntity probe;
    private int created;

    @BeforeEach
    void setUp() {
        fixtures = new RecipeListFixtures(usersRepository, recipesRepository, mediaRepository, listsRepository,
                entriesRepository);
        owner = fixtures.user("owner");
        fixtures.authenticateAs(owner);
        probe = fixtures.recipe("probe");
    }

    @AfterEach
    void tearDown() {
        fixtures.signOut();
    }

    /** Lists that each hold a few recipes with pictures, so every aggregate has something to aggregate. */
    private void createLists(int howMany) {
        for (int index = 0; index < howMany; index++) {
            RecipeListEntity list = fixtures.list(owner, "Liste " + created++);
            for (int slot = 0; slot < 3; slot++) {
                MediaEntity media = fixtures.media();
                fixtures.put(list, fixtures.recipeWithCovers("recipe " + created + "-" + slot, media),
                        NOON.plusSeconds(slot));
            }
            fixtures.put(list, probe, NOON);
        }
    }

    private Statistics statistics() {
        return entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    /** Statements prepared while {@code call} runs, from a cold persistence context. */
    private long statementsDuring(Runnable call) {
        entityManager.flush();
        entityManager.clear();
        Statistics statistics = statistics();
        statistics.clear();
        call.run();
        return statistics.getPrepareStatementCount();
    }

    @Test
    void listingTheListsCostsTheSameFewStatementsWhetherThereAreTwoOrTwenty() {
        createLists(2);
        long forTwo = statementsDuring(() -> assertThat(service.list(null)).hasSize(2));

        createLists(18);
        long forTwenty = statementsDuring(() -> assertThat(service.list(null)).hasSize(20));

        // The lists, the counts and the mosaics: three statements, however many lists there are.
        assertThat(forTwo).isEqualTo(3);
        assertThat(forTwenty).isEqualTo(forTwo);
    }

    @Test
    void askingWhichListsHoldARecipeAddsAFixedNumberOfStatements_notOnePerList() {
        createLists(2);
        long forTwo = statementsDuring(() -> assertThat(service.list(probe.getId())).hasSize(2));

        createLists(18);
        long forTwenty = statementsDuring(() -> assertThat(service.list(probe.getId())).hasSize(20));

        // Plus: does the recipe exist, and which lists hold it.
        assertThat(forTwo).isEqualTo(5);
        assertThat(forTwenty).isEqualTo(forTwo);
    }

    @Test
    void theAggregatesAreRightAtTwentyListsToo() {
        createLists(20);
        entityManager.flush();
        entityManager.clear();

        List<RecipeListResponse> lists = service.list(probe.getId());

        assertThat(lists).hasSize(20).allSatisfy(list -> {
            assertThat(list.recipeCount()).isEqualTo(4);
            assertThat(list.coverMediaIds()).hasSize(3);
            assertThat(list.containsRecipe()).isTrue();
        });
    }

    @Test
    void readingOneListIsAFixedNumberOfStatementsToo() {
        createLists(1);
        String id = listsRepository.findByOwnerIdOrderByNormalizedNameAscNameAsc(owner.getId()).getFirst().getId();

        long statements = statementsDuring(() -> assertThat(service.get(id).recipeCount()).isEqualTo(4));

        // The list, its count and its mosaic. The owner is compared by id and never loaded.
        assertThat(statements).isEqualTo(3);
    }
}
