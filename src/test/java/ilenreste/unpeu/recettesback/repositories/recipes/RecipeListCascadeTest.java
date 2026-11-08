package ilenreste.unpeu.recettesback.repositories.recipes;

import ilenreste.unpeu.recettesback.configuration.JpaAuditingConfig;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeListEntity;
import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.repositories.media.MediaRepository;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
import ilenreste.unpeu.recettesback.services.users.CurrentUserService;
import ilenreste.unpeu.recettesback.support.RecipeListFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Deleting a recipe removes it from every list. Deleting a list deletes its entries, never the recipes."
 * <p>
 * Proved against the real database, not against the mapping: the cascade is an {@code ON DELETE CASCADE}
 * on the foreign keys, so what is under test is the constraint {@code ddl-auto=update} actually created,
 * and no persistence-context state can stand in for it. Each delete is flushed before anything is read back.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, CurrentUserService.class})
class RecipeListCascadeTest {

    private static final Instant NOON = Instant.parse("2026-11-08T12:00:00Z");

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
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private RecipeListFixtures fixtures;
    private UserEntity owner;

    @BeforeEach
    void setUp() {
        fixtures = new RecipeListFixtures(usersRepository, recipesRepository, mediaRepository, listsRepository,
                entriesRepository);
        owner = fixtures.user("owner");
        fixtures.authenticateAs(owner);
    }

    @AfterEach
    void tearDown() {
        fixtures.signOut();
    }

    @Test
    void deletingAListDeletesItsEntries_andNeverTheRecipes() {
        RecipeListEntity doomed = fixtures.list(owner, "Doomed");
        RecipeListEntity kept = fixtures.list(owner, "Kept");
        RecipeEntity tarte = fixtures.recipe("Tarte");
        RecipeEntity soupe = fixtures.recipe("Soupe");
        fixtures.put(doomed, tarte, NOON);
        fixtures.put(doomed, soupe, NOON);
        fixtures.put(kept, tarte, NOON);

        listsRepository.delete(doomed);
        entityManager.flush();
        entityManager.clear();

        assertThat(entriesRepository.countByListId(doomed.getId())).as("its entries went with it").isZero();
        assertThat(entriesRepository.countByListId(kept.getId())).as("another list's entries stay").isEqualTo(1);
        assertThat(recipesRepository.existsById(tarte.getId())).isTrue();
        assertThat(recipesRepository.existsById(soupe.getId())).isTrue();
    }

    @Test
    void deletingARecipeRemovesItFromEveryList_andNeverTheLists() {
        RecipeEntity doomed = fixtures.recipe("Doomed");
        RecipeEntity kept = fixtures.recipe("Kept");
        RecipeListEntity one = fixtures.list(owner, "Une");
        RecipeListEntity other = fixtures.list(owner, "Autre");
        fixtures.put(one, doomed, NOON);
        fixtures.put(other, doomed, NOON);
        fixtures.put(one, kept, NOON);

        recipesRepository.deleteById(doomed.getId());
        entityManager.flush();
        entityManager.clear();

        assertThat(entriesRepository.findListIdsHolding(doomed.getId(), owner.getId())).isEmpty();
        assertThat(entriesRepository.countByListId(one.getId())).as("the other recipe stays").isEqualTo(1);
        assertThat(entriesRepository.countByListId(other.getId())).isZero();
        assertThat(listsRepository.existsById(one.getId())).isTrue();
        assertThat(listsRepository.existsById(other.getId())).isTrue();
    }

    /** Reads the constraint rather than trusting the annotation: if {@code @OnDelete} is ever lost, this says so. */
    @Test
    void bothForeignKeysAreCreatedWithOnDeleteCascade() {
        List<String> rules = jdbcTemplate.queryForList("""
                SELECT rc.delete_rule
                FROM information_schema.referential_constraints rc
                JOIN information_schema.table_constraints tc ON tc.constraint_name = rc.constraint_name
                WHERE tc.table_name = 'recipe_list_entries'
                """, String.class);

        assertThat(rules).hasSize(2).containsOnly("CASCADE");
    }
}
