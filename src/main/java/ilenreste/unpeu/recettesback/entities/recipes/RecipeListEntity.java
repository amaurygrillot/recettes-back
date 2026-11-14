package ilenreste.unpeu.recettesback.entities.recipes;

import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
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
import org.hibernate.annotations.DynamicUpdate;
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
 * {@code updatedAt} moves when the name or the emoji changes, or when the list's own photo is set, replaced or
 * removed, and <em>only</em> then — the entries live in another table, and adding a recipe does not dirty this
 * row. The photo is a column of this row, which is why {@code @LastModifiedDate} sees it change by itself.
 * <p>
 * The name is unique <strong>per owner after normalisation</strong>, and that is a real constraint:
 * the service checks first for a clean 409, and the constraint covers the race. See
 * {@link #UNIQUE_NAME_CONSTRAINT}.
 * <p>
 * <strong>{@code @DynamicUpdate}</strong>: without it Hibernate writes every column on an {@code UPDATE}, so a
 * rename that loaded the list before a photo was attached and flushed after it would write the old, empty
 * {@code cover_media_id} back — a photo silently lost, its row and files left behind. With it a rename writes
 * the name, the emoji and {@code updated_at}, a photo write writes {@code cover_media_id} and
 * {@code updated_at}, and the two cannot clobber each other.
 */
@Getter
@Setter
@Entity
@DynamicUpdate
@EntityListeners(AuditingEntityListener.class)
@Table(name = "recipe_lists", uniqueConstraints = {
        @UniqueConstraint(name = RecipeListEntity.UNIQUE_NAME_CONSTRAINT, columnNames = {"owner_id", "normalized_name"}),
        // A photo belongs to one list and to nothing else. The unique index is also what serves the NOT EXISTS probes
        // that keep a list's photo out of the public lookups of media and out of the orphan sweep.
        @UniqueConstraint(name = RecipeListEntity.UNIQUE_COVER_CONSTRAINT, columnNames = "cover_media_id")
})
public class RecipeListEntity {

    /**
     * Named, because {@code ApiExceptionHandler} recognises a lost race by this name and answers the same
     * typed 409 the service's own check does. Its index, led by {@code owner_id}, also serves "the lists of
     * one member".
     */
    public static final String UNIQUE_NAME_CONSTRAINT = "uk_recipe_lists_owner_normalized_name";

    public static final String UNIQUE_COVER_CONSTRAINT = "uk_recipe_lists_cover_media";

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

    /**
     * The list's own photo, or {@code null}. A {@code media} row that <strong>only this list references</strong>:
     * it is created in the transaction that attaches it, is never accepted as a recipe's picture or an ingredient's
     * icon, and is deleted — row and files — when it is replaced, removed or the list is deleted. That it is
     * private is implied by this reference: the lookups public code uses to resolve a media id exclude every row
     * a list points at ({@code MediaRepository#findPublicById}).
     * <p>
     * Lazy, and read for its id only on the paths that list many lists, so a page of lists costs no statement
     * for it. No cascade: nothing but the list's cover service writes or deletes it, explicitly.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cover_media_id")
    private MediaEntity cover;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * What clients call {@code coverVersion}: the id of the photo, or {@code null} without one. A new photo is a
     * new row with a new id, and nothing can change the bytes behind an id, so it changes exactly when the photo
     * does. Reading the id of a lazy reference does not load it.
     */
    public String coverVersion() {
        return cover == null ? null : cover.getId();
    }
}
