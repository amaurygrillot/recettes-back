package ilenreste.unpeu.recettesback.mappers.recipes;

import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeCoverPictureEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeEntity;
import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.mappers.reference.ReferenceMapper;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeSummaryResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The listing row is the one place the cover pictures are read in bulk, so what it exposes of them is
 * pinned here: the thumbnail is the first picture, and the whole list is there, in order, for the
 * card that shows them as a small slider.
 */
class RecipeMapperTest {

    private final RecipeMapper mapper = new RecipeMapper(mock(ReferenceMapper.class));

    private static RecipeEntity recipeWithCovers(String... mediaIds) {
        UserEntity author = new UserEntity();
        author.setId("u-1");
        author.setUsername("mamie");
        RecipeEntity recipe = new RecipeEntity();
        recipe.setId("r-1");
        recipe.setTitle("Tarte");
        recipe.setAuthor(author);
        for (int position = 0; position < mediaIds.length; position++) {
            MediaEntity media = new MediaEntity();
            media.setId(mediaIds[position]);
            RecipeCoverPictureEntity cover = new RecipeCoverPictureEntity();
            cover.setRecipe(recipe);
            cover.setMedia(media);
            cover.setPosition(position);
            recipe.getCoverPictures().add(cover);
        }
        return recipe;
    }

    @Test
    void listsEveryCoverPictureInOrder_theFirstBeingTheThumbnail() {
        RecipeSummaryResponse summary = mapper.toSummary(recipeWithCovers("m-1", "m-2", "m-3"), Map.of());

        assertThat(summary.coverMediaIds()).containsExactly("m-1", "m-2", "m-3");
        assertThat(summary.coverMediaId()).isEqualTo("m-1");
    }

    @Test
    void hasNoThumbnailAndAnEmptyListWithoutACover() {
        RecipeSummaryResponse summary = mapper.toSummary(recipeWithCovers(), Map.of());

        assertThat(summary.coverMediaIds()).isEmpty();
        assertThat(summary.coverMediaId()).isNull();
    }
}
