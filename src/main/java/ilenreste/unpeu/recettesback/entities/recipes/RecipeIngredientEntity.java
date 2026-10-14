package ilenreste.unpeu.recettesback.entities.recipes;

import ilenreste.unpeu.recettesback.entities.reference.IngredientEntity;
import ilenreste.unpeu.recettesback.entities.reference.UnitEntity;
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

import java.math.BigDecimal;

/**
 * One line of a recipe's ingredient list: "200 g de farine, tamisee".
 * <p>
 * {@code quantity} is {@code NUMERIC(10,3)} and <strong>nullable</strong>. Null
 * covers "sel, poivre" and "de l'huile", where no amount is meaningful.
 * {@link BigDecimal} and never {@code double}: floating-point rounding artifacts
 * have no place in a quantity a human typed. Three decimals is enough for 0.5
 * tsp or 0.25 L without inviting nonsense precision.
 * <p>
 * {@code unit} is nullable too — null means a bare count ("3 oeufs"). A
 * {@code PIECE} unit row was considered and rejected: it forces every recipe to
 * pick a unit and puts "3 pieces oeufs" in front of the renderer.
 * <p>
 * {@code note} carries the qualifier belonging to <em>this line</em> rather than
 * to the ingredient itself ("finement hache", "a temperature ambiante").
 * Without it, people encode that into the ingredient name and the shared
 * ingredients table fills up with rows like {@code oignon finement hache}.
 * <p>
 * Keeping the amount numeric is what makes serving-scaling and an aggregated
 * shopping list possible later; both become schema migrations if it is a string.
 */
@Getter
@Setter
@Entity
@Table(name = "recipe_ingredients",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_recipe_ingredients_position", columnNames = {"group_id", "position"}),
        indexes = {
                // Both serve the "is this reference row still in use?" check that guards DELETE.
                @Index(name = "idx_recipe_ingredients_ingredient_id", columnList = "ingredient_id"),
                @Index(name = "idx_recipe_ingredients_unit_id", columnList = "unit_id")
        })
public class RecipeIngredientEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private RecipeIngredientGroupEntity group;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ingredient_id", nullable = false)
    private IngredientEntity ingredient;

    @Column(precision = 10, scale = 3)
    private BigDecimal quantity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_id")
    private UnitEntity unit;

    @Column
    private String note;

    @Column(nullable = false)
    private int position;
}
