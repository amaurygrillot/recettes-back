package ilenreste.unpeu.recettesback.entities.recipes;

import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * Links a recipe to one of its cover pictures, in order.
 * <p>
 * A link row rather than a direct media reference on the recipe, because a
 * recipe has several covers and each carries its own position and alt text.
 * Deleting a recipe deletes these rows and merely dereferences the media.
 */
@Getter
@Setter
@Entity
@Table(name = "recipe_cover_pictures",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_recipe_cover_pictures_position", columnNames = {"recipe_id", "position"}),
        indexes = {
                // The unique constraint above indexes (recipe_id, position), whose leading column
                // cannot serve a lookup by media_id - which is exactly what the orphan sweep does,
                // once per candidate row.
                @Index(name = "idx_recipe_cover_pictures_media_id", columnList = "media_id")
        })
public class RecipeCoverPictureEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipe_id", nullable = false)
    private RecipeEntity recipe;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_id", nullable = false)
    private MediaEntity media;

    @Column(nullable = false)
    private int position;

    @Column
    private String altText;
}
