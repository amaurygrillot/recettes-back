package ilenreste.unpeu.recettesback.repositories.media;

import ilenreste.unpeu.recettesback.configuration.JpaAuditingConfig;
import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeListEntity;
import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipeListEntriesRepository;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipeListsRepository;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipesRepository;
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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lookups that decide which {@code media} rows public code may see, run against the real PostgreSQL, because a
 * mocked repository proves nothing about JPQL. A row a recipe list points at is that list's own photo: private, so
 * the public lookups must not find it, the orphan sweep must not take it, and the disk use must count it.
 */
// Exactly the annotations of the other repository tests, so that it shares their Spring context and adds no
// connection pool to the run: docs/testing-database.md.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, CurrentUserService.class})
class MediaRepositoryTest {

    private static final Instant LATER = Instant.now().plusSeconds(3600);

    @Autowired
    private MediaRepository mediaRepository;
    @Autowired
    private RecipeListsRepository listsRepository;
    @Autowired
    private RecipeListEntriesRepository entriesRepository;
    @Autowired
    private RecipesRepository recipesRepository;
    @Autowired
    private UsersRepository usersRepository;
    @Autowired
    private EntityManager entityManager;

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

    private MediaEntity coverOf(RecipeListEntity list) {
        MediaEntity photo = fixtures.media();
        list.setCover(photo);
        listsRepository.saveAndFlush(list);
        entityManager.clear();
        return photo;
    }

    @Test
    void thePublicLookups_doNotFindAListsPhoto_butFindAnyOtherRow() {
        MediaEntity photo = coverOf(fixtures.list(owner, "Desserts"));
        MediaEntity ordinary = fixtures.media();

        assertThat(mediaRepository.findPublicById(photo.getId())).as("a list's photo").isEmpty();
        assertThat(mediaRepository.findPublicById(ordinary.getId())).isPresent();
        assertThat(mediaRepository.findPublicById("no-such-media")).isEmpty();
        assertThat(mediaRepository.findAllPublicById(List.of(photo.getId(), ordinary.getId(), "no-such-media")))
                .extracting(MediaEntity::getId).containsExactly(ordinary.getId());
        // The inherited lookup sees every row, which is why public code must not use it.
        assertThat(mediaRepository.findById(photo.getId())).isPresent();
    }

    @Test
    void anAttachedPhotoIsNotAnOrphan_andADetachedOneIs() {
        RecipeListEntity list = fixtures.list(owner, "Desserts");
        MediaEntity photo = coverOf(list);
        MediaEntity neverAttached = fixtures.media();

        assertThat(mediaRepository.findOrphans(LATER)).extracting(MediaEntity::getId)
                .as("attached, so a list references it").doesNotContain(photo.getId())
                .contains(neverAttached.getId());

        RecipeListEntity reloaded = listsRepository.findById(list.getId()).orElseThrow();
        reloaded.setCover(null);
        listsRepository.saveAndFlush(reloaded);
        entityManager.clear();

        assertThat(mediaRepository.findOrphans(LATER)).extracting(MediaEntity::getId)
                .as("detached, nothing references it any more").contains(photo.getId());
    }

    @Test
    void aMediaRowAPictureOrAnIconReferences_isStillNotAnOrphan() {
        MediaEntity cover = fixtures.media();
        fixtures.recipeWithCovers("Tarte", cover);
        entityManager.clear();

        assertThat(mediaRepository.findOrphans(LATER)).extracting(MediaEntity::getId).doesNotContain(cover.getId());
    }

    @Test
    void aListsPhotoCountsInTheBytesItsUploaderUses() {
        long before = mediaRepository.totalBytesUploadedBy(owner.getId());
        MediaEntity photo = coverOf(fixtures.list(owner, "Desserts"));

        assertThat(mediaRepository.totalBytesUploadedBy(owner.getId()) - before).isEqualTo(photo.getSizeBytes());
    }
}
