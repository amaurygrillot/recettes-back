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

/** Links a step to one of its illustrations, in order. */
@Getter
@Setter
@Entity
@Table(name = "recipe_step_pictures",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_recipe_step_pictures_position", columnNames = {"step_id", "position"}),
        indexes = {
                @Index(name = "idx_recipe_step_pictures_media_id", columnList = "media_id")
        })
public class RecipeStepPictureEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "step_id", nullable = false)
    private RecipeStepEntity step;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_id", nullable = false)
    private MediaEntity media;

    @Column(nullable = false)
    private int position;

    @Column
    private String altText;
}
