package ilenreste.unpeu.recettesback.repositories.reference;

import ilenreste.unpeu.recettesback.entities.reference.ReferenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.Optional;

/**
 * The three lookups every reference table needs, declared once.
 * <p>
 * {@code @NoRepositoryBean} so Spring Data does not try to instantiate this
 * itself — only the four concrete interfaces below it become beans.
 *
 * @param <T> the reference entity this repository stores
 */
@NoRepositoryBean
public interface ReferenceRepository<T extends ReferenceEntity> extends JpaRepository<T, String> {

    /**
     * Lets create return <em>which</em> existing row collided, so the client can
     * select it instead of retrying blindly.
     */
    Optional<T> findByNormalizedName(String normalizedName);

    /** The pre-check that produces a clean 409 on create. */
    boolean existsByNormalizedName(String normalizedName);
}
