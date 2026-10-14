package ilenreste.unpeu.recettesback.entities.reference;

import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A shared ingredient. The one reference table any authenticated user may add
 * to, since a recipe cannot be written without the ingredients it uses.
 * <p>
 * Editing and deleting stay admin-only: renaming a shared row silently rewrites
 * every recipe referencing it, and deleting one would orphan them. That is a
 * different kind of power from adding a missing row.
 * <p>
 * {@code createdBy} — inherited from {@code AuditableEntity} — matters here more
 * than anywhere else precisely because creation is open: it is the only record
 * of who added a shared row, and it is what an admin reads before merging a
 * duplicate.
 * <p>
 * Plurals are deliberately <strong>not</strong> normalized away: {@code oeuf}
 * and {@code oeufs} stay two rows. Stemming French correctly is a real NLP
 * problem and getting it wrong silently merges unrelated ingredients; a human
 * noticing a duplicate and an admin merging it is the cheaper failure mode at
 * this scale.
 */
@Getter
@Setter
@Entity
@Table(name = "ingredients", indexes = {
        // PostgreSQL does not index foreign-key columns automatically - only primary keys and
        // unique constraints get one for free - and the orphan sweep probes this column through
        // NOT EXISTS for every candidate row.
        @Index(name = "idx_ingredients_icon_media_id", columnList = "icon_media_id")
})
public class IngredientEntity extends ReferenceEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "icon_media_id")
    private MediaEntity icon;
}
