package ilenreste.unpeu.recettesback.entities.recipes;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One instruction in a recipe.
 * <p>
 * {@code position} is maintained by the service, which renumbers 0..n-1 from the
 * order of the incoming request on every write. {@code @OrderColumn} was
 * considered and rejected: it makes Hibernate issue extra UPDATE statements on
 * reordering and behaves badly with nulls in the collection. An explicit column
 * the service owns is boring, predictable, and readable from plain SQL when
 * debugging.
 * <p>
 * The {@code UNIQUE (recipe_id, position)} constraint also provides the index
 * for looking steps up by recipe, which is the direction those lookups go — so
 * no separate {@code recipe_id} index is needed.
 */
@Getter
@Setter
@Entity
@Table(name = "recipe_steps", uniqueConstraints =
@UniqueConstraint(name = "uk_recipe_steps_position", columnNames = {"recipe_id", "position"}))
public class RecipeStepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipe_id", nullable = false)
    private RecipeEntity recipe;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, columnDefinition = "text")
    private String instruction;

    @OneToMany(mappedBy = "step", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("position")
    private Set<RecipeStepPictureEntity> pictures = new LinkedHashSet<>();
}
