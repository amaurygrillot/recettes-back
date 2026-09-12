package ilenreste.unpeu.recettesback.entities.recipes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The block of text shown above everything else on a recipe — the "here is what
 * this dish is and where it comes from" paragraph, before the ingredients and the
 * steps.
 * <p>
 * Optional: a recipe with no introduction has no row here, and the response
 * carries {@code null}. That is the distinction a nullable column could not draw
 * as cleanly — with a column, "no introduction" and "an introduction someone
 * emptied" are both the empty string.
 *
 * <h2>Why its own table rather than a column on {@code recipes}</h2>
 * It is a <em>section</em>, in the same sense as a step or an ingredient group:
 * an addressable block of content with an id of its own, which is what lets an
 * editor or a comment anchor refer to it. Recipes already have a
 * {@code recommendations} column, and that one genuinely is a plain field on the
 * recipe — a note rendered beside it, never addressed on its own. Keeping the two
 * shapes apart is what stops "add a field to recipes" from being the answer to
 * every future block of prose.
 * <p>
 * {@code text} is {@code text}, not {@code varchar}: this field is authored in a
 * WYSIWYG editor and arrives as markup, so any length cap is a number someone
 * will hit mid-sentence. PostgreSQL stores {@code text} and {@code varchar}
 * identically, so the unbounded type costs nothing — see {@link RecipeStepEntity},
 * where the same reasoning applies to {@code instruction}.
 * <p>
 * The primary key is the recipe's own id ({@link MapsId}), so the one-per-recipe
 * rule is the primary key rather than a unique constraint bolted on beside a
 * surrogate one.
 */
@Getter
@Setter
@Entity
@Table(name = "recipe_introductions")
public class RecipeIntroductionEntity {

    @Id
    private String recipeId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipe_id", nullable = false)
    private RecipeEntity recipe;

    @Column(nullable = false, columnDefinition = "text")
    private String text;
}
