package ilenreste.unpeu.recettesback.mappers.recipes;

import ilenreste.unpeu.recettesback.entities.recipes.RecipeCoverPictureEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeIngredientEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeIngredientGroupEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeStepEntity;
import ilenreste.unpeu.recettesback.entities.reference.CategoryEntity;
import ilenreste.unpeu.recettesback.entities.reference.TagEntity;
import ilenreste.unpeu.recettesback.mappers.reference.ReferenceMapper;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeAuthorResponse;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeIngredientGroupResponse;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeIngredientResponse;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipePictureResponse;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeResponse;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeStepResponse;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeSummaryResponse;
import ilenreste.unpeu.recettesback.models.reference.responses.CategoryResponse;
import ilenreste.unpeu.recettesback.models.reference.responses.TagResponse;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Entity graph to response.
 * <p>
 * <strong>Ordered collections are iterated, never re-sorted and never
 * re-derived.</strong> {@code position} is a storage detail; the API contract is
 * the order of the returned array, and the entities already arrive in
 * {@code position} order because every such association is mapped with
 * {@code @OrderBy("position")}. Re-sorting here would duplicate that rule in a
 * second place, and the two would eventually disagree.
 * <p>
 * {@code categories} and {@code tags} are the exception: they have no position,
 * so they are sorted by name to give a stable output.
 */
@Component
public class RecipeMapper {

    private static final Comparator<CategoryEntity> BY_CATEGORY_NAME =
            Comparator.comparing(CategoryEntity::getName, String.CASE_INSENSITIVE_ORDER);
    private static final Comparator<TagEntity> BY_TAG_NAME =
            Comparator.comparing(TagEntity::getName, String.CASE_INSENSITIVE_ORDER);

    private final ReferenceMapper referenceMapper;

    public RecipeMapper(ReferenceMapper referenceMapper) {
        this.referenceMapper = referenceMapper;
    }

    public RecipeResponse toResponse(RecipeEntity recipe) {
        return new RecipeResponse(
                recipe.getId(),
                recipe.getTitle(),
                recipe.getRecommendations(),
                toAuthor(recipe),
                toCategories(recipe),
                toTags(recipe),
                recipe.getCoverPictures().stream().map(this::toPicture).toList(),
                recipe.getIngredientGroups().stream().map(this::toGroup).toList(),
                recipe.getSteps().stream().map(this::toStep).toList(),
                recipe.getCreatedAt(),
                recipe.getUpdatedAt()
        );
    }

    /**
     * The listing row. Nothing here loads a step, an ingredient group or any
     * cover picture past the first — a list page must not cost one full recipe
     * load per row.
     */
    public RecipeSummaryResponse toSummary(RecipeEntity recipe) {
        return new RecipeSummaryResponse(
                recipe.getId(),
                recipe.getTitle(),
                firstCoverMediaId(recipe),
                toCategories(recipe),
                toTags(recipe),
                toAuthor(recipe),
                recipe.getCreatedAt()
        );
    }

    /**
     * First by position, which is well-defined only because the collection is
     * ordered. {@code findFirst()} on an unordered {@code HashSet} would pick a
     * different picture between two identical requests.
     */
    private String firstCoverMediaId(RecipeEntity recipe) {
        return recipe.getCoverPictures().stream()
                .findFirst()
                .map(cover -> cover.getMedia().getId())
                .orElse(null);
    }

    private RecipeAuthorResponse toAuthor(RecipeEntity recipe) {
        return new RecipeAuthorResponse(recipe.getAuthor().getId(), recipe.getAuthor().getUsername());
    }

    private List<CategoryResponse> toCategories(RecipeEntity recipe) {
        return recipe.getCategories().stream().sorted(BY_CATEGORY_NAME)
                .map(referenceMapper::toResponse).toList();
    }

    private List<TagResponse> toTags(RecipeEntity recipe) {
        return recipe.getTags().stream().sorted(BY_TAG_NAME)
                .map(referenceMapper::toResponse).toList();
    }

    private RecipePictureResponse toPicture(RecipeCoverPictureEntity cover) {
        return new RecipePictureResponse(cover.getMedia().getId(), cover.getAltText());
    }

    private RecipeStepResponse toStep(RecipeStepEntity step) {
        return new RecipeStepResponse(
                step.getInstruction(),
                step.getPictures().stream()
                        .map(picture -> new RecipePictureResponse(
                                picture.getMedia().getId(), picture.getAltText()))
                        .toList());
    }

    private RecipeIngredientGroupResponse toGroup(RecipeIngredientGroupEntity group) {
        return new RecipeIngredientGroupResponse(
                group.getTitle(),
                group.getIngredients().stream().map(this::toIngredient).toList());
    }

    private RecipeIngredientResponse toIngredient(RecipeIngredientEntity line) {
        return new RecipeIngredientResponse(
                referenceMapper.toResponse(line.getIngredient()),
                line.getQuantity(),
                line.getUnit() == null ? null : referenceMapper.toResponse(line.getUnit()),
                line.getNote());
    }
}
