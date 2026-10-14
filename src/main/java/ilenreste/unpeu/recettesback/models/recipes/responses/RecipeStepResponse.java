package ilenreste.unpeu.recettesback.models.recipes.responses;

import java.util.List;

/** One instruction and its illustrations, both in order. */
public record RecipeStepResponse(String instruction, List<RecipePictureResponse> pictures) {
}
