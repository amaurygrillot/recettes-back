package ilenreste.unpeu.recettesback.services.reference;

import ilenreste.unpeu.recettesback.entities.reference.ReferenceEntity;
import ilenreste.unpeu.recettesback.exceptions.ResourceConflictException;
import ilenreste.unpeu.recettesback.exceptions.ResourceNotFoundException;
import ilenreste.unpeu.recettesback.repositories.reference.ReferenceRepository;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Sort;

import java.util.List;

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
 * @param <T> the reference entity this service manages
 */
@Log4j2
public abstract class AbstractReferenceService<T extends ReferenceEntity> {

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

    /** Everything, name-sorted, so the output is stable between identical requests. */
    public List<T> findAll() {
        return repository.findAll(Sort.by("name"));
    }

    public T findById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of(resourceName(), id));
    }

    /**
     * Creates a row, or refuses with a 409 naming the row it collided with — so
     * the client can select the existing one rather than retrying blindly.
     * <p>
     * The pre-check is for the message, not for correctness: check-then-insert
     * races, and the unique constraint on {@code normalized_name} is what
     * actually guarantees the invariant. A lost race surfaces as a
     * {@code DataIntegrityViolationException} and is mapped to the same 409.
     */
    protected T create(String name) {
        String normalized = normalizer.normalize(name);
        repository.findByNormalizedName(normalized).ifPresent(existing -> {
            throw new ResourceConflictException("A %s named '%s' already exists (id %s)."
                    .formatted(resourceName(), existing.getName(), existing.getId()));
        });

        T entity = newEntity();
        entity.setName(name.trim());
        entity.setNormalizedName(normalized);
        log.info("Creating {} '{}'", resourceName(), entity.getName());
        return repository.save(entity);
    }

    /**
     * Renames a row. A rename that normalizes to the same value is allowed — it
     * is how {@code oeuf} becomes {@code Œuf} for display without colliding with
     * itself.
     */
    protected T rename(T entity, String name) {
        String normalized = normalizer.normalize(name);
        if (!normalized.equals(entity.getNormalizedName())) {
            repository.findByNormalizedName(normalized).ifPresent(existing -> {
                throw new ResourceConflictException("A %s named '%s' already exists (id %s)."
                        .formatted(resourceName(), existing.getName(), existing.getId()));
            });
        }
        log.info("Renaming {} {} to '{}'", resourceName(), entity.getId(), name.trim());
        entity.setName(name.trim());
        entity.setNormalizedName(normalized);
        return entity;
    }

    /**
     * Deletes a row, or refuses with a 409 if any recipe still references it.
     * <p>
     * Never a cascade. Silently gutting other people's recipes because an admin
     * tidied the tag list is not an acceptable side effect of a DELETE.
     */
    public void delete(String id) {
        T entity = findById(id);
        if (isUsed(id)) {
            log.info("Refusing to delete {} {}: still referenced by at least one recipe",
                    resourceName(), id);
            throw new ResourceConflictException(
                    "This %s is still used by at least one recipe and cannot be deleted."
                            .formatted(resourceName()));
        }
        log.info("Deleting {} {}", resourceName(), id);
        repository.delete(entity);
    }
}
