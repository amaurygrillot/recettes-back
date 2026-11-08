package ilenreste.unpeu.recettesback.repositories.recipes;

import ilenreste.unpeu.recettesback.configuration.JpaAuditingConfig;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeListEntity;
import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.repositories.media.MediaRepository;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
import ilenreste.unpeu.recettesback.services.users.CurrentUserService;
import ilenreste.unpeu.recettesback.support.RecipeListFixtures;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The queries and the constraints of {@code recipe_lists}, against the real PostgreSQL.
 * <p>
 * The unique constraint is the part a mock cannot show: the service checks the name first, and this
 * constraint is what holds when two requests pass that check together.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, CurrentUserService.class})
class RecipeListsRepositoryTest {

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
    private UserEntity other;

    @BeforeEach
    void setUp() {
        fixtures = new RecipeListFixtures(usersRepository, recipesRepository, mediaRepository, listsRepository,
                entriesRepository);
        owner = fixtures.user("owner");
        other = fixtures.user("other");
    }

    @Test
    void listsAMembersListsByNormalisedName_ignoringAccentsAndCase_andNobodyElses() {
        fixtures.list(owner, "zeste");
        fixtures.list(owner, "Épices");
        fixtures.list(owner, "banane");
        fixtures.list(other, "Aaa, someone else's");

        assertThat(listsRepository.findByOwnerIdOrderByNormalizedNameAscNameAsc(owner.getId()))
                .extracting(RecipeListEntity::getName)
                .containsExactly("banane", "Épices", "zeste");
    }

    @Test
    void countsOnlyTheMembersOwnLists() {
        fixtures.list(owner, "Une");
        fixtures.list(owner, "Deux");
        fixtures.list(other, "Trois");

        assertThat(listsRepository.countByOwnerId(owner.getId())).isEqualTo(2);
        assertThat(listsRepository.countByOwnerId(other.getId())).isEqualTo(1);
    }

    @Test
    void findsAListByNormalisedNameWithinOneMember() {
        RecipeListEntity tartes = fixtures.list(owner, "Tartes salées");

        assertThat(listsRepository.findByOwnerIdAndNormalizedName(owner.getId(), "tartes salees"))
                .map(RecipeListEntity::getId).contains(tartes.getId());
        assertThat(listsRepository.findByOwnerIdAndNormalizedName(other.getId(), "tartes salees")).isEmpty();
    }

    @Test
    void twoMembersMayBothHaveAListWithTheSameName() {
        fixtures.list(owner, "Desserts");

        assertThat(fixtures.list(other, "Desserts").getId()).isNotNull();
    }

    /**
     * The race the service's pre-check loses: it said "free", then the insert found the name taken. The
     * violated constraint has to be the named one, because that name is how the exception handler
     * recognises this race and answers the same typed 409 as the pre-check does.
     */
    @Test
    void theUniqueConstraintRefusesTheSameNormalisedNameForOneMember_andIsNamed() {
        fixtures.list(owner, "Desserts");

        assertThatThrownBy(() -> fixtures.list(owner, "  désserts "))
                .as("a different spelling that normalises to the same name")
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(exception -> assertThat(constraintNameOf(exception))
                        .isEqualTo(RecipeListEntity.UNIQUE_NAME_CONSTRAINT));
    }

    private static String constraintNameOf(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
        }
        throw new AssertionError("no Hibernate ConstraintViolationException in the cause chain of " + exception);
    }

    /**
     * A 100-character Korean name is 300 characters once normalised (NFD turns a syllable into up to three
     * jamo). The column is sized for that: at 100 this insert would die on {@code value too long}, a 500 for
     * a perfectly valid name.
     */
    @Test
    void aNameOfTheMaximumLengthFitsEvenWhenNormalisationTriplesIt() {
        String hangul = "한".repeat(100); // 한, which NFD splits into three jamo
        RecipeListEntity list = fixtures.list(owner, hangul);
        entityManager.clear();

        RecipeListEntity reloaded = listsRepository.findById(list.getId()).orElseThrow();

        assertThat(reloaded.getName()).hasSize(100);
        assertThat(reloaded.getNormalizedName()).hasSize(RecipeListEntity.NORMALIZED_NAME_MAX_LENGTH);
    }

    @Test
    void stampsBothTimestampsOnInsert_andMovesOnlyUpdatedAtOnAChange() throws InterruptedException {
        RecipeListEntity list = fixtures.list(owner, "Desserts");
        Instant created = list.getCreatedAt();
        assertThat(created).isNotNull();
        assertThat(list.getUpdatedAt()).isEqualTo(created);

        Thread.sleep(5);
        list.setEmoji("🍝");
        listsRepository.saveAndFlush(list);

        assertThat(list.getCreatedAt()).isEqualTo(created);
        assertThat(list.getUpdatedAt()).isAfter(created);
    }

    @Test
    void savingAListWithNothingChangedLeavesUpdatedAtAlone() throws InterruptedException {
        RecipeListEntity list = fixtures.list(owner, "Desserts");
        Instant updated = list.getUpdatedAt();

        Thread.sleep(5);
        list.setName("Desserts");
        list.setEmoji(RecipeListFixtures.CAKE);
        listsRepository.saveAndFlush(list);

        assertThat(list.getUpdatedAt()).isEqualTo(updated);
    }
}
