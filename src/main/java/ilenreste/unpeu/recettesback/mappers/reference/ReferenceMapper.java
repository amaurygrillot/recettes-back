package ilenreste.unpeu.recettesback.mappers.reference;

import ilenreste.unpeu.recettesback.entities.reference.CategoryEntity;
import ilenreste.unpeu.recettesback.entities.reference.IngredientEntity;
import ilenreste.unpeu.recettesback.entities.reference.TagEntity;
import ilenreste.unpeu.recettesback.entities.reference.UnitEntity;
import ilenreste.unpeu.recettesback.models.reference.responses.CategoryResponse;
import ilenreste.unpeu.recettesback.models.reference.responses.IngredientResponse;
import ilenreste.unpeu.recettesback.models.reference.responses.TagResponse;
import ilenreste.unpeu.recettesback.models.reference.responses.UnitResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Entity to response for the four reference tables.
 * <p>
 * Note what never crosses: {@code normalizedName} is a storage detail carrying
 * the unique constraint, and a client matching on it would be coupled to our
 * normalization rules.
 */
@Component
public class ReferenceMapper {

    /**
     * The parent id is passed in rather than read off the entity, because the
     * entity does not carry it: a category's parent lives in
     * {@code categories_relation}, and only {@code CategoryService} — which holds
     * the whole edge list — can say whether a given row is a root. Giving this
     * mapper a repository so it could look the edge up itself would turn every
     * category in a list into its own query.
     *
     * @param parentCategoryId the direct parent category, or null for a main
     *                           category. Null and not the stored self-reference:
     *                           see {@link CategoryResponse}
     */
    public CategoryResponse toResponse(CategoryEntity category, String parentCategoryId) {
        return new CategoryResponse(category.getId(), category.getName(),
                category.getCategoryType(), parentCategoryId);
    }

    public TagResponse toResponse(TagEntity tag) {
        return new TagResponse(tag.getId(), tag.getName());
    }

    public UnitResponse toResponse(UnitEntity unit) {
        return new UnitResponse(unit.getId(), unit.getName(), unit.getAbbreviation());
    }

    /**
     * Reads only the icon's id, never the icon entity — the association is lazy
     * and {@code getId()} on a Hibernate proxy is answered from the foreign key
     * already in hand, so a list of ingredients costs no extra query for icons.
     */
    public IngredientResponse toResponse(IngredientEntity ingredient) {
        return new IngredientResponse(ingredient.getId(), ingredient.getName(),
                ingredient.getIcon() == null ? null : ingredient.getIcon().getId());
    }

    public List<TagResponse> toTagResponses(List<TagEntity> tags) {
        return tags.stream().map(this::toResponse).toList();
    }

    public List<UnitResponse> toUnitResponses(List<UnitEntity> units) {
        return units.stream().map(this::toResponse).toList();
    }
}
