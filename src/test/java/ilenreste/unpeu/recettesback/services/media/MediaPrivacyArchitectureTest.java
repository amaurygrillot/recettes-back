package ilenreste.unpeu.recettesback.services.media;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
import ilenreste.unpeu.recettesback.models.media.MediaVariant;
import ilenreste.unpeu.recettesback.repositories.media.MediaRepository;
import ilenreste.unpeu.recettesback.services.recipes.RecipeListCoverService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The privacy of a recipe list's photo rests on one convention: <strong>no code path that starts from a request reads a
 * {@code media} row by id without the public filter.</strong> A list's photo is a {@code media} row that nothing marks as
 * private except the list that points at it, so {@code GET /media/{id}}, a recipe's pictures and an ingredient's icon all
 * look rows up through {@code findPublicById} and {@code findAllPublicById}, which leave it out. The inherited
 * {@code findById}, {@code findAllById} and {@code getReferenceById} see every row, and so does
 * {@link MediaService#open}, which serves a row without asking who is asking.
 * <p>
 * Until this test the convention was a javadoc on {@link MediaRepository}, which a new caller of {@code findById} has no
 * reason to read. Only {@link MediaService} and {@link RecipeListCoverService} (which has already decided that the caller
 * may see the list) may call them. The rule reads the compiled production classes, so it sees the call and not the
 * text, and the second test proves that the rule notices each forbidden call. The reasoning, and why it is ArchUnit and
 * not a scan of the sources, is in docs/recipe-list-cover.md.
 * <p>
 * <strong>What it cannot see</strong>, because the bytecode does not say: a call through a variable typed as the generic
 * {@code JpaRepository<MediaEntity, String>} instead of {@link MediaRepository}, and a method reference to an inherited
 * method such as {@code mediaRepository::findAllById}, which javac compiles to a handle on the interface that
 * <em>declares</em> the method ({@code ListCrudRepository}) and which therefore looks the same as
 * {@code categoriesRepository::findAllById}. Nothing in the code is typed the first way, and the second is what a review
 * of a new use of {@code MediaRepository} has to look for: the public lookups are declared on {@link MediaRepository}
 * itself, so a reference to one of those is a different method and is not affected.
 */
class MediaPrivacyArchitectureTest {

    private static final String PRODUCTION = "ilenreste.unpeu.recettesback";

    private static final ArchRule ONLY_THE_TWO_SERVICES_READ_ANY_ROW = noClasses()
            .that().doNotHaveFullyQualifiedName(MediaService.class.getName())
            .and().doNotHaveFullyQualifiedName(RecipeListCoverService.class.getName())
            .should().callMethod(MediaRepository.class, "findById", Object.class)
            .orShould().callMethod(MediaRepository.class, "findAllById", Iterable.class)
            .orShould().callMethod(MediaRepository.class, "getReferenceById", Object.class)
            .orShould().callMethod(MediaService.class, "open", MediaEntity.class, MediaVariant.class)
            .because("a list's photo is a media row that only its list makes private: every read of a row by an id "
                    + "must go through findPublicById or findAllPublicById, or through the list's own service");

    @Test
    void noClassButTheTwoServicesReadsAMediaRowOfAnyIdWithoutThePublicFilter() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(PRODUCTION);

        assertThat(production.contain(MediaService.class)).as("the production classes were found").isTrue();
        assertThat(production.contain(RecipeListCoverService.class)).isTrue();
        ONLY_THE_TWO_SERVICES_READ_ANY_ROW.check(production);
    }

    /**
     * The rule would be worth nothing if it matched nothing: a call that has the wrong descriptor, or an owner the
     * compiler spells differently, passes for ever. Each forbidden call, made from a class of this test's own, must be
     * reported, by name.
     */
    @Test
    void theRuleNoticesEachForbiddenCall() {
        JavaClasses offender = new ClassFileImporter().importClasses(Offender.class);

        assertThatThrownBy(() -> ONLY_THE_TWO_SERVICES_READ_ANY_ROW.check(offender))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Offender.byId()")
                .hasMessageContaining("Offender.byIds()")
                .hasMessageContaining("Offender.byReference()")
                .hasMessageContaining("Offender.served(");
    }

    /** What the rule forbids, done by a class that is not one of the two services. Never loaded: only read. */
    @SuppressWarnings("unused")
    private static final class Offender {
        private MediaRepository repository;
        private MediaService service;

        Object byId() {
            return repository.findById("an-id");
        }

        Object byIds() {
            return repository.findAllById(List.of("an-id"));
        }

        Object byReference() {
            return repository.getReferenceById("an-id");
        }

        Object served(MediaEntity row) {
            return service.open(row, MediaVariant.FULL);
        }
    }
}
