package ilenreste.unpeu.recettesback.entities.recipes;

import jakarta.persistence.Embeddable;

import java.io.Serializable;

/**
 * The composite primary key of a recipe list entry: the list and the recipe. It is what makes "a recipe
 * appears at most once in a list" a fact of the schema rather than a convention.
 */
@Embeddable
public record RecipeListEntryId(String listId, String recipeId) implements Serializable {
}
