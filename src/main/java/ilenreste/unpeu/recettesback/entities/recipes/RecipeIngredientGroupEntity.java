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
 * A titled (or untitled) block of ingredient lines.
 * <p>
 * {@code title} is nullable on purpose: most recipes have a single unnamed
 * ingredient list, and forcing a title there would make every such recipe carry
 * a meaningless "Ingredients" heading. Null means "render the list with no
 * heading". Recipes that do split ("Pour la pate" / "Pour la garniture") title
 * every group.
 */
@Getter
@Setter
@Entity
@Table(name = "recipe_ingredient_groups", uniqueConstraints =
@UniqueConstraint(name = "uk_recipe_ingredient_groups_position", columnNames = {"recipe_id", "position"}))
public class RecipeIngredientGroupEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipe_id", nullable = false)
    private RecipeEntity recipe;

    @Column(nullable = false)
    private int position;

    /** Null means the list renders with no heading. */
    @Column
    private String title;

    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("position")
    private Set<RecipeIngredientEntity> ingredients = new LinkedHashSet<>();
}
