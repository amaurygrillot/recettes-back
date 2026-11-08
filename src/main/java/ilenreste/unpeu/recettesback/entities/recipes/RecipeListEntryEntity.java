package ilenreste.unpeu.recettesback.entities.recipes;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/**
 * "This recipe is in this list", and since when.
 * <p>
 * <strong>Both foreign keys cascade on delete, in the database.</strong> Deleting a list deletes its
 * entries; deleting a recipe removes it from every list. It is an integrity rule, so it is not left to
 * {@code RecipeService.delete}: nothing that deletes a recipe — now, from a future code path, or from a
 * hand-run statement — can leave an entry pointing at nothing or be refused by one. Neither direction
 * ever deletes the other side: a list never takes a recipe with it, and a recipe never takes a list.
 * {@code @OnDelete} is what lets {@code ddl-auto=update} create the constraints with the table.
 * <p>
 * Entries are inserted by {@code RecipeListEntriesRepository.insertIfAbsent}, not by {@code save}: an
 * idempotent add has to be atomic, and a JPA insert after an {@code exists} check loses the race on the
 * primary key. The mapping is still needed for the schema and for the queries that read and delete
 * entries.
 * <p>
 * {@code addedAt} is set by the service from the application {@code Clock}, not by auditing: ordering by
 * it is product behaviour that tests must be able to control.
 */
@Getter
@Setter
@Entity
@Table(name = "recipe_list_entries", indexes = {
        // The primary key's leading column is list_id, which cannot serve a lookup by recipe, and PostgreSQL
        // does not index foreign keys on its own: this is the cascade from a deleted recipe, and the question
        // "which of my lists hold this recipe".
        @Index(name = "idx_recipe_list_entries_recipe_id", columnList = "recipe_id")
})
public class RecipeListEntryEntity {

    @EmbeddedId
    private RecipeListEntryId id;

    @MapsId("listId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "list_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private RecipeListEntity list;

    @MapsId("recipeId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipe_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private RecipeEntity recipe;

    @Column(nullable = false)
    private Instant addedAt;
}
