package ilenreste.unpeu.recettesback.entities.recipes;

import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.models.recipes.requests.RecipeListRequest;
import ilenreste.unpeu.recettesback.validation.SingleEmoji;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * A member's private list of recipes. Specified in docs/recipe-lists.md.
 * <p>
 * <strong>The owner is set by the service, not stamped by {@code @CreatedBy}</strong> as
 * {@link RecipeEntity#getAuthor()} is: ownership is what decides who may read this row, and a column
 * like that should be written by code that says so rather than by an auditing side effect. With
 * {@code @CreatedBy}, a missing security context would also be a {@code NOT NULL} violation — a 500
 * — where it is a 401. It is {@code updatable = false}: a list never changes hands.
 * <p>
 * It does not extend {@code AuditableEntity}: the owner is the only person who can write the row, so
 * {@code created_by} and {@code updated_by} would hold the owner's id forever. Timestamps only.
 * {@code updatedAt} moves when the name or the emoji changes and <em>only</em> then — the entries
 * live in another table, and adding a recipe does not dirty this row.
 * <p>
 * The name is unique <strong>per owner after normalisation</strong>, and that is a real constraint:
 * the service checks first for a clean 409, and the constraint covers the race. See
 * {@link #UNIQUE_NAME_CONSTRAINT}.
 */
@Getter
@Setter
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "recipe_lists", uniqueConstraints = @UniqueConstraint(
        name = RecipeListEntity.UNIQUE_NAME_CONSTRAINT, columnNames = {"owner_id", "normalized_name"}))
public class RecipeListEntity {

    /**
     * Named, because {@code ApiExceptionHandler} recognises a lost race by this name and answers the same
     * typed 409 the service's own check does. Its index, led by {@code owner_id}, also serves "the lists of
     * one member".
     */
    public static final String UNIQUE_NAME_CONSTRAINT = "uk_recipe_lists_owner_normalized_name";

    /**
     * Wider than the name: normalisation goes through NFD, which turns a Hangul syllable into up to three jamo,
     * so a valid 100-character Korean name is 300 characters once normalised. At 100 the insert would die on
     * {@code value too long} and surface as a 500. Three times the name limit is the worst case.
     */
    public static final int NORMALIZED_NAME_MAX_LENGTH = 3 * RecipeListRequest.NAME_MAX_LENGTH;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false, updatable = false)
    private UserEntity owner;

    /** As the owner typed it, trimmed. Display only. */
    @Column(nullable = false, length = RecipeListRequest.NAME_MAX_LENGTH)
    private String name;

    /** What the unique constraint sits on. */
    @Column(nullable = false, length = NORMALIZED_NAME_MAX_LENGTH)
    private String normalizedName;

    /** Text, never an image: the front draws it. */
    @Column(nullable = false, length = SingleEmoji.MAX_LENGTH)
    private String emoji;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
