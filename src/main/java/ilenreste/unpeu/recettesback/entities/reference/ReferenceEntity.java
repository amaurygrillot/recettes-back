package ilenreste.unpeu.recettesback.entities.reference;

import ilenreste.unpeu.recettesback.entities.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

/**
 * Shared shape of the four reference tables: a display name, and a normalized
 * name carrying the uniqueness.
 * <p>
 * <strong>Why two columns instead of a plain {@code unique} on {@code name}:</strong>
 * "unique values" has to mean unique <em>to a human</em>, not unique as a byte
 * sequence. Without normalization the ingredients table happily accepts
 * {@code Oeuf}, {@code oeuf}, {@code Œuf}, {@code " oeuf "} and {@code OEUF} as
 * five distinct rows, and the property that justified having a table at all —
 * one canonical row per real-world thing — is dead within a week.
 * <p>
 * {@code name} is stored exactly as the user typed it and is what the API
 * returns; {@code normalizedName} is what the unique constraint sits on and what
 * prefix search runs against, which is also what makes typing {@code oeuf} find
 * {@code Œuf}.
 * <p>
 * The constraint is a <strong>real database constraint</strong>, not just a
 * service-side {@code existsBy} check: check-then-insert races under concurrent
 * requests. The service does check first so the common case returns a clean 409
 * with a useful message, but the constraint is what actually guarantees the
 * invariant.
 *
 * @see ilenreste.unpeu.recettesback.services.reference.ReferenceNameNormalizer
 */
@Getter
@Setter
@MappedSuperclass
public abstract class ReferenceEntity extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** As the user typed it. Display only. */
    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String normalizedName;
}
