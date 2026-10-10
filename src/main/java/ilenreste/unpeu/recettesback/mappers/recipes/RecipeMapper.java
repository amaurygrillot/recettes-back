package ilenreste.unpeu.recettesback.mappers.recipes;

import ilenreste.unpeu.recettesback.entities.recipes.RecipeCoverPictureEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeIngredientEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeIngredientGroupEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeIntroductionEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeStepEntity;
import ilenreste.unpeu.recettesback.entities.reference.CategoryEntity;
import ilenreste.unpeu.recettesback.entities.reference.TagEntity;
import ilenreste.unpeu.recettesback.mappers.reference.ReferenceMapper;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeAuthorResponse;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeIngredientGroupResponse;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeIngredientResponse;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeIntroductionResponse;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipePictureResponse;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeResponse;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeStepResponse;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeSummaryResponse;
import ilenreste.unpeu.recettesback.models.reference.responses.CategoryResponse;
import ilenreste.unpeu.recettesback.models.reference.responses.TagResponse;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

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
 * <p>
 * <strong>Why the category edges are passed in.</strong> A {@code CategoryResponse}
 * carries the id of its parent category, and that lives in
 * {@code categories_relation} — not on the entity Hibernate hydrated. Looking it
 * up here would mean either a repository dependency in a mapper, or one query per
 * category per recipe per row of a listing. Instead {@code RecipeService} reads the
 * whole edge map once per request (two columns, tens of rows) and hands it down, so
 * a twenty-row page costs exactly one extra query rather than sixty.
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

    /**
     * @param categoryParentIds {@code categoryId -> parentCategoryId} for the
     *                            whole taxonomy, roots absent — see
     *                            {@code CategoryRelationsRepository.parentIdsByCategoryId()}
     */
    public RecipeResponse toResponse(RecipeEntity recipe, Map<String, String> categoryParentIds) {
        return new RecipeResponse(
                recipe.getId(),
                recipe.getTitle(),
                toIntroduction(recipe.getIntroduction()),
                recipe.getRecommendations(),
                toAuthor(recipe),
                toCategories(recipe, categoryParentIds),
                toTags(recipe),
                recipe.getCoverPictures().stream().map(this::toPicture).toList(),
                recipe.getIngredientGroups().stream().map(this::toGroup).toList(),
                recipe.getSteps().stream().map(this::toStep).toList(),
                recipe.getCreatedAt(),
                recipe.getUpdatedAt()
        );
    }

    /** Null when the author wrote no introduction — never an object with empty text. */
    private RecipeIntroductionResponse toIntroduction(RecipeIntroductionEntity introduction) {
        return introduction == null
                ? null
                : new RecipeIntroductionResponse(introduction.getRecipeId(), introduction.getText());
    }

    /**
     * The listing row. Nothing here loads a step, an ingredient group or any
     * cover picture past the first — a list page must not cost one full recipe
     * load per row.
     */
    public RecipeSummaryResponse toSummary(RecipeEntity recipe, Map<String, String> categoryParentIds) {
        return new RecipeSummaryResponse(
                recipe.getId(),
                recipe.getTitle(),
                firstCoverMediaId(recipe),
                toCategories(recipe, categoryParentIds),
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

    private List<CategoryResponse> toCategories(RecipeEntity recipe,
                                                Map<String, String> categoryParentIds) {
        return recipe.getCategories().stream().sorted(BY_CATEGORY_NAME)
                .map(category -> referenceMapper.toResponse(
                        category, categoryParentIds.get(category.getId())))
                .toList();
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
