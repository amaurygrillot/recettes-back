package ilenreste.unpeu.recettesback.services.reference;

import ilenreste.unpeu.recettesback.entities.reference.ReferenceEntity;
import ilenreste.unpeu.recettesback.exceptions.ResourceConflictException;
import ilenreste.unpeu.recettesback.exceptions.ResourceNotFoundException;
import ilenreste.unpeu.recettesback.repositories.reference.ReferenceRepository;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Sort;

import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * The create / rename / delete rules every reference table shares.
 * <p>
 * Written once rather than four times, because the interesting parts — what
 * counts as a duplicate, and refusing to delete something still in use — are
 * exactly the parts that would drift between four copies and leave one table
 * silently accepting {@code Oeuf} next to {@code oeuf}.
 * <p>
 * Subclasses supply what genuinely differs: how to make an empty entity, what to
 * call the thing in an error message, and how to ask whether any recipe still
 * points at it.
 *
 * <h2>What a caller may assume from a normal return</h2>
 * Every method here either <strong>returns a row that is really in the database
 * or throws</strong>. There is no "returned null because it did not work" path
 * and no boolean to test, which is what lets the controllers above stay free of
 * {@code if (result == null)} branches without being optimistic:
 * <ul>
 *   <li>the id names nothing → {@link ResourceNotFoundException}, 404;</li>
 *   <li>the name collides with an existing row → {@link ResourceConflictException},
 *       409, naming the row it collided with;</li>
 *   <li>the row is still referenced by a recipe → {@link ResourceConflictException},
 *       409, and nothing is deleted;</li>
 *   <li>a create loses the check-then-insert race → {@code DataIntegrityViolationException}
 *       from the unique constraint, mapped to the same 409 by {@code ApiExceptionHandler};</li>
 *   <li>anything else → the transaction rolls back and the exception reaches the
 *       advice as a 500. The row is not half-written.</li>
 * </ul>
 * Each write also ends in a single {@code save}, and the returned entity is the
 * one the persistence context flushed — never the detached input — so a mapper
 * reading it back sees the generated id and the stored, trimmed name.
 *
 * @param <T> the reference entity this service manages
 * @param <R> the response record that entity is mapped to. Declared here so
 *            {@link #list()} and {@link #get(String)} can live in one place
 *            instead of being re-typed per subclass as
 *            {@code mapper.toResponse(findById(id))} four times over
 */
@Log4j2
public abstract class AbstractReferenceService<T extends ReferenceEntity, R> {

    /**
     * The one ordering for reference rows, shared with every in-memory caller
     * (see {@code RecipeMapper} and {@code CategoryTaxonomy}).
     * <p>
     * By {@code normalizedName} and not by {@code name}: normalization has already
     * folded case <em>and</em> accents, so this agrees with the
     * {@code Sort.by("normalizedName")} that {@link #findAll()} pushes to
     * PostgreSQL. The previous spelling — {@code String.CASE_INSENSITIVE_ORDER} on
     * {@code name} in Java, column collation on {@code name} in SQL — disagreed
     * with itself: {@code GET /tags} and the {@code tags} array inside
     * {@code GET /recipes/{id}} returned the same rows in different orders, and
     * neither put {@code Épices} where a French reader looks for it.
     */
    public static final Comparator<ReferenceEntity> BY_NAME =
            Comparator.comparing(ReferenceEntity::getNormalizedName);

    protected final ReferenceRepository<T> repository;
    protected final ReferenceNameNormalizer normalizer;

    protected AbstractReferenceService(ReferenceRepository<T> repository, ReferenceNameNormalizer normalizer) {
        this.repository = repository;
        this.normalizer = normalizer;
    }

    protected abstract T newEntity();

    /** How this thing is named in an error message, e.g. {@code "category"}. */
    protected abstract String resourceName();

    /** Whether any recipe still references the row with this id. */
    protected abstract boolean isUsed(String id);

    /**
     * Maps one row to its response record. The subclass's only remaining say in
     * what {@link #list()} and {@link #get(String)} return.
     */
    protected abstract R toResponse(T entity);

    /**
     * Last chance to refuse a delete, or to clear away rows that reference this
     * one from outside the recipes graph. Runs after existence and the in-use
     * check, before the row itself goes.
     * <p>
     * A hook rather than an override of {@link #delete}, so a subclass cannot
     * accidentally get the order wrong — and every guard is guaranteed to run
     * before anything is removed, instead of being unwound by a rollback
     * afterwards. Does nothing by default.
     */
    protected void beforeDelete(T entity) {
        // Nothing to do for a reference table whose only inbound references are recipes.
    }

    /**
     * Everything, name-sorted, so the output is stable between identical requests.
     * <p>
     * Sorted by {@code normalizedName} to agree with {@link #BY_NAME}; the column
     * already carries a unique index, so the ordering is free.
     */
    @Transactional(readOnly = true)
    public List<T> findAll() {
        return repository.findAll(Sort.by("normalizedName"));
    }

    @Transactional(readOnly = true)
    public T findById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of(resourceName(), id));
    }

    /** Every row, name-sorted, as responses. */
    @Transactional(readOnly = true)
    public List<R> list() {
        return findAll().stream().map(this::toResponse).toList();
    }

    /**
     * One row as a response.
     *
     * @throws ResourceNotFoundException if the id names nothing
     */
    @Transactional(readOnly = true)
    public R get(String id) {
        return toResponse(findById(id));
    }

    /**
     * Creates a row, or refuses with a 409 naming the row it collided with — so
     * the client can select the existing one rather than retrying blindly.
     * <p>
     * The pre-check is for the message, not for correctness: check-then-insert
     * races, and the unique constraint on {@code normalized_name} is what
     * actually guarantees the invariant. A lost race surfaces as a
     * {@code DataIntegrityViolationException} and is mapped to the same 409.
     * <p>
     * {@code customizer} runs <strong>before</strong> the insert, not after it.
     * That ordering is the fix for a real trap: the previous version saved the
     * bare name first and let subclasses set their extra columns afterwards,
     * relying on dirty checking to issue a second UPDATE. It worked only because
     * every such column happened to be nullable — the first person to mark one
     * {@code NOT NULL} would have got a constraint violation on insert, from code
     * that looks correct.
     *
     * @param customizer sets whatever the subclass adds on top of the shared
     *                   name columns, on an entity that has not been persisted yet
     */
    protected T create(String name, Consumer<T> customizer) {
        String normalized = normalizer.normalize(name);
        repository.findByNormalizedName(normalized).ifPresent(existing -> {
            throw new ResourceConflictException("A %s named '%s' already exists (id %s)."
                    .formatted(resourceName(), existing.getName(), existing.getId()));
        });

        T entity = newEntity();
        entity.setName(name.trim());
        entity.setNormalizedName(normalized);
        customizer.accept(entity);

        T saved = repository.save(entity);
        log.info("Created {} {} ('{}')", resourceName(), saved.getId(), saved.getName());
        return saved;
    }

    /**
     * Loads a row, renames it, lets the subclass apply its own columns, and saves
     * once.
     * <p>
     * A rename that normalizes to the same value is allowed — it is how
     * {@code oeuf} becomes {@code Œuf} for display without colliding with itself.
     *
     * @throws ResourceNotFoundException if the id names nothing
     * @throws ResourceConflictException if the new name is already taken by another row
     */
    protected T update(String id, String name, Consumer<T> customizer) {
        T entity = findById(id);

        String normalized = normalizer.normalize(name);
        if (!normalized.equals(entity.getNormalizedName())) {
            repository.findByNormalizedName(normalized).ifPresent(existing -> {
                throw new ResourceConflictException("A %s named '%s' already exists (id %s)."
                        .formatted(resourceName(), existing.getName(), existing.getId()));
            });
        }
        entity.setName(name.trim());
        entity.setNormalizedName(normalized);
        customizer.accept(entity);

        T saved = repository.save(entity);
        log.info("Updated {} {} ('{}')", resourceName(), id, saved.getName());
        return saved;
    }

    /**
     * Deletes a row, or refuses with a 409 if any recipe still references it.
     * <p>
     * Note the order: existence first, so an unknown id is a 404 rather than a
     * silent success. {@code deleteById} would have answered 204 for a row that
     * never existed, which tells the caller their state is in sync with ours when
     * it is not.
     * <p>
     * Never a cascade. Silently gutting other people's recipes because an admin
     * tidied the tag list is not an acceptable side effect of a DELETE.
     *
     * <h2>{@code @Transactional} is load-bearing here, not decorative</h2>
     * This method issues four statements, and {@link #beforeDelete} may add more.
     * Without a transaction each one commits on its own, which breaks the
     * promise made at the top of this class ("the transaction rolls back ... The
     * row is not half-written") in a way that is specific and reachable:
     * {@code CategoryService.beforeDelete} deletes the row's {@code
     * categories_relation} edge, and if the delete below then fails — a
     * concurrent {@code POST /recipes} files a recipe under this category
     * between {@link #isUsed} and here, so the join-table foreign key refuses —
     * the edge is already committed gone while the category survives. That
     * leaves a category with no relation row, which
     * {@code CategoryRelationsRepository} documents as "unambiguously a bug":
     * {@code CategoryTaxonomy} lists roots by {@code categoryType} and children
     * by edge, so a surviving subcategory is in neither and disappears from
     * {@code GET /categories/hierarchy} entirely.
     * <p>
     * It was the only write in the four reference services without one — every
     * {@code create*} and {@code update*} in the subclasses was annotated, which
     * is exactly why the gap was easy to miss.
     *
     * @throws ResourceNotFoundException if the id names nothing
     * @throws ResourceConflictException if a recipe still references the row, in
     *                                   which case nothing is deleted
     */
    @Transactional
    public void delete(String id) {
        T entity = findById(id);
        if (isUsed(id)) {
            log.info("Refusing to delete {} {}: still referenced by at least one recipe",
                    resourceName(), id);
            throw new ResourceConflictException(
                    "This %s is still used by at least one recipe and cannot be deleted."
                            .formatted(resourceName()));
        }
        beforeDelete(entity);
        repository.delete(entity);
        log.info("Deleted {} {}", resourceName(), id);
    }
}
