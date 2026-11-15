package ilenreste.unpeu.recettesback.services.media;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.ReferencedClassObject;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
import ilenreste.unpeu.recettesback.models.media.MediaVariant;
import ilenreste.unpeu.recettesback.repositories.media.MediaRepository;
import ilenreste.unpeu.recettesback.services.recipes.RecipeListCoverService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.lang.conditions.ArchConditions.accessTargetWhere;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The privacy of a recipe list's photo rests on one convention: <strong>no code path that starts from a request reads a
 * {@code media} row by id without the public filter.</strong> A list's photo is a {@code media} row that nothing marks as
 * private except the list that points at it, so {@code GET /media/{id}}, a recipe's pictures and an ingredient's icon all
 * look rows up through {@code findPublicById} and {@code findAllPublicById}, which leave it out. Everything else the
 * repository offers sees every row — the inherited {@code findById}, {@code existsById}, {@code findAll}, {@code count}…
 * — and so does {@link MediaService#open}, which serves a row without asking who is asking, and so would an
 * {@code EntityManager} asked for a {@code MediaEntity}.
 * <p>
 * Only {@link MediaService} and {@link RecipeListCoverService} (which has already decided that the caller may see the
 * list) may do those things. <strong>The rule is an allow-list</strong>: any other class may call exactly the two public
 * lookups on {@link MediaRepository}, and nothing else of it. A deny-list of the methods somebody thought of let
 * {@code existsById} (the most plausible leak: a validator that accepts a list photo's id as a recipe picture because the
 * row « exists »), {@code findAll}, {@code count} and any query method added tomorrow through. The list is the real one:
 * the only calls outside the two services are {@code IngredientService.resolveIcon} and
 * {@code RecipeService.resolveMedia}, and both are public lookups. A class that needs another method adds it to
 * {@link #PUBLIC_LOOKUPS} on purpose, which is the whole point.
 * <p>
 * The rules read the compiled production classes, so they see the call and not the text, and the self-tests prove that
 * each rule notices what it forbids and lets through what it allows. The reasoning, and why it is ArchUnit and not a
 * scan of the sources, is in docs/recipe-list-cover.md.
 * <p>
 * <strong>What they cannot see</strong>, because the bytecode does not say: a call through a variable typed as the
 * generic {@code JpaRepository<MediaEntity, String>} instead of {@link MediaRepository}, a method reference to an
 * <em>inherited</em> method such as {@code mediaRepository::findAllById} (javac compiles it to a handle on the interface
 * that declares the method, {@code ListCrudRepository}, which looks the same as the
 * {@code categoriesRepository::findAllById} that {@code RecipeService} legitimately uses), and a {@code @Query} on
 * <em>another</em> repository that selects {@code MediaEntity} in a JPQL string. A reference to a method declared on
 * {@link MediaRepository} itself is seen, which is why {@code mediaRepository::findAllPublicById} is allowed by name.
 */
class MediaPrivacyArchitectureTest {

    private static final String PRODUCTION = "ilenreste.unpeu.recettesback";

    /** The only methods of {@link MediaRepository} that a class other than the two services may use. */
    private static final Set<String> PUBLIC_LOOKUPS = Set.of("findPublicById", "findAllPublicById");

    /** « The class is not one of the two services, nor something nested in one of them. » */
    private static final DescribedPredicate<JavaClass> NOT_ONE_OF_THE_TWO_SERVICES =
            not(belongToAnyOf(MediaService.class, RecipeListCoverService.class));

    private static DescribedPredicate<JavaAccess<?>> accessTo(Class<?> owner, String description,
                                                              Predicate<String> methodName) {
        return DescribedPredicate.describe(description, access ->
                access.getTarget().getOwner().isEquivalentTo(owner) && methodName.test(access.getTarget().getName()));
    }

    private static final ArchRule ONLY_THE_PUBLIC_LOOKUPS_ARE_CALLED = noClasses()
            .that(NOT_ONE_OF_THE_TWO_SERVICES)
            .should(accessTargetWhere(accessTo(MediaRepository.class,
                    "a method of MediaRepository other than " + PUBLIC_LOOKUPS, name -> !PUBLIC_LOOKUPS.contains(name))))
            .because("a list's photo is a media row that only its list makes private: every read of a row by an id "
                    + "must go through findPublicById or findAllPublicById, or through the list's own service, so "
                    + "nothing else of the repository may be reached from anywhere else");

    private static final ArchRule NO_ROW_IS_SERVED_WITHOUT_ASKING_WHO_IS_ASKING = noClasses()
            .that(NOT_ONE_OF_THE_TWO_SERVICES)
            .should(accessTargetWhere(accessTo(MediaService.class, "MediaService.open", "open"::equals)))
            .because("open serves a row without asking who is asking: the caller must already have decided");

    private static final ArchCondition<JavaClass> REFER_TO_THE_MEDIA_ENTITY_CLASS_OBJECT =
            new ArchCondition<>("refer to the class object MediaEntity.class") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    for (ReferencedClassObject reference : javaClass.getReferencedClassObjects()) {
                        if (reference.getValue().isEquivalentTo(MediaEntity.class)) {
                            events.add(SimpleConditionEvent.satisfied(reference, reference.getOwner().getFullName()
                                    + " refers to MediaEntity.class in " + reference.getSourceCodeLocation()));
                        }
                    }
                }
            };

    /**
     * {@code EntityManager.find(MediaEntity.class, id)}, {@code getReference} and a typed {@code createQuery} read a row of
     * any id. The bytecode of the call says « {@code EntityManager.find} » and nothing about which entity (ArchUnit does not
     * see constant arguments), so the rule is on what those calls all need: the class object.
     */
    private static final ArchRule NO_ENTITY_MANAGER_IS_ASKED_FOR_A_MEDIA_ROW = noClasses()
            .that(NOT_ONE_OF_THE_TWO_SERVICES)
            .should(REFER_TO_THE_MEDIA_ENTITY_CLASS_OBJECT)
            .because("EntityManager.find, getReference and a typed query read a media row of any id, a list's photo "
                    + "included");

    private static JavaClasses production() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(PRODUCTION);
        assertThat(production.contain(MediaService.class)).as("the production classes were found").isTrue();
        assertThat(production.contain(RecipeListCoverService.class)).isTrue();
        return production;
    }

    @Test
    void noClassButTheTwoServicesCallsAnythingOnMediaRepositoryButThePublicLookups() {
        ONLY_THE_PUBLIC_LOOKUPS_ARE_CALLED.check(production());
    }

    @Test
    void noClassButTheTwoServicesServesARowWithoutAskingWhoIsAsking() {
        NO_ROW_IS_SERVED_WITHOUT_ASKING_WHO_IS_ASKING.check(production());
    }

    @Test
    void noClassButTheTwoServicesAsksAnEntityManagerForAMediaRow() {
        NO_ENTITY_MANAGER_IS_ASKED_FOR_A_MEDIA_ROW.check(production());
    }

    /**
     * The rules would be worth nothing if they matched nothing: a call that has the wrong descriptor, or an owner the
     * compiler spells differently, passes for ever. Every inherited read, write and query, called from a class of this
     * test's own, must be reported by name — the ones a deny-list missed (the existence check, {@code findAll} in each
     * overload, {@code count}, the deprecated {@code getById} and {@code getOne}) first among them — and so must a
     * reference to a method that {@link MediaRepository} declares itself.
     */
    @Test
    void theAllowListNoticesEveryMethodOfTheRepositoryButThePublicLookups() {
        JavaClasses offender = new ClassFileImporter().importClasses(Offender.class);

        assertThatThrownBy(() -> ONLY_THE_PUBLIC_LOOKUPS_ARE_CALLED.check(offender))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Offender.byExistsById()")
                .hasMessageContaining("Offender.byFindAll()")
                .hasMessageContaining("Offender.byFindAllSorted()")
                .hasMessageContaining("Offender.byFindAllPaged()")
                .hasMessageContaining("Offender.byCount()")
                .hasMessageContaining("Offender.byGetById()")
                .hasMessageContaining("Offender.byGetOne()")
                .hasMessageContaining("Offender.byId()")
                .hasMessageContaining("Offender.byIds()")
                .hasMessageContaining("Offender.byReference()")
                .hasMessageContaining("Offender.bySave(")
                .hasMessageContaining("Offender.byDelete(")
                .hasMessageContaining("Offender.byTotalBytes()")
                .hasMessageContaining("Offender.byOrphans()")
                .hasMessageContaining("Offender.byOrphansReference()");
    }

    @Test
    void theOtherTwoRulesNoticeServingARowAndTheMediaEntityClassObject() {
        JavaClasses offender = new ClassFileImporter().importClasses(Offender.class);

        assertThatThrownBy(() -> NO_ROW_IS_SERVED_WITHOUT_ASKING_WHO_IS_ASKING.check(offender))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Offender.served(");
        assertThatThrownBy(() -> NO_ENTITY_MANAGER_IS_ASKED_FOR_A_MEDIA_ROW.check(offender))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Offender.entityFind()")
                .hasMessageContaining("Offender.entityReference()")
                .hasMessageContaining("Offender.entityTypedQuery()");
    }

    /**
     * The other half of the proof: a rule that reports everything passes the first self-test and fails the build. What the
     * code base really does — the two public lookups, called and referenced, and a method reference to a method that is
     * <em>not</em> the repository's — must pass all three rules.
     */
    @Test
    void thePublicLookupsAreAllowed_calledAndReferenced() {
        JavaClasses allowed = new ClassFileImporter().importClasses(Allowed.class);

        assertThatCode(() -> ONLY_THE_PUBLIC_LOOKUPS_ARE_CALLED.check(allowed)).doesNotThrowAnyException();
        assertThatCode(() -> NO_ROW_IS_SERVED_WITHOUT_ASKING_WHO_IS_ASKING.check(allowed)).doesNotThrowAnyException();
        assertThatCode(() -> NO_ENTITY_MANAGER_IS_ASKED_FOR_A_MEDIA_ROW.check(allowed)).doesNotThrowAnyException();
    }

    /** What the rules forbid, done by a class that is not one of the two services. Never loaded: only read. */
    @SuppressWarnings("unused")
    private static final class Offender {
        private MediaRepository repository;
        private MediaService service;
        private EntityManager entityManager;

        Object byExistsById() {
            return repository.existsById("an-id");
        }

        Object byFindAll() {
            return repository.findAll();
        }

        Object byFindAllSorted() {
            return repository.findAll(Sort.unsorted());
        }

        Object byFindAllPaged() {
            return repository.findAll(Pageable.unpaged());
        }

        Object byCount() {
            return repository.count();
        }

        Object byGetById() {
            return repository.getById("an-id");
        }

        Object byGetOne() {
            return repository.getOne("an-id");
        }

        Object byId() {
            return repository.findById("an-id");
        }

        Object byIds() {
            return repository.findAllById(List.of("an-id"));
        }

        Object byReference() {
            return repository.getReferenceById("an-id");
        }

        Object bySave(MediaEntity row) {
            return repository.save(row);
        }

        void byDelete(MediaEntity row) {
            repository.delete(row);
        }

        Object byTotalBytes() {
            return repository.totalBytesUploadedBy("a-user");
        }

        Object byOrphans() {
            return repository.findOrphans(Instant.now());
        }

        /** A reference to a method {@link MediaRepository} declares itself: seen, and not one of the two allowed. */
        Object byOrphansReference() {
            Function<Instant, List<MediaEntity>> orphans = repository::findOrphans;
            return orphans;
        }

        Object served(MediaEntity row) {
            return service.open(row, MediaVariant.FULL);
        }

        Object entityFind() {
            return entityManager.find(MediaEntity.class, "an-id");
        }

        Object entityReference() {
            return entityManager.getReference(MediaEntity.class, "an-id");
        }

        Object entityTypedQuery() {
            return entityManager.createQuery("SELECT m FROM MediaEntity m", MediaEntity.class);
        }
    }

    /** What production code does today, and must go on being allowed. Never loaded: only read. */
    @SuppressWarnings("unused")
    private static final class Allowed {
        private MediaRepository repository;

        Object byPublicId() {
            return repository.findPublicById("an-id");
        }

        Object byPublicIds() {
            return repository.findAllPublicById(List.of("an-id"));
        }

        /** The shape of {@code RecipeService.resolveMedia}. */
        Object byPublicIdsReference() {
            Function<Collection<String>, List<MediaEntity>> lookup = repository::findAllPublicById;
            return lookup;
        }
    }
}
