package ilenreste.unpeu.recettesback.models.recipes.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One picture attached to a recipe or a step, in the order it appears in the
 * enclosing list.
 * <p>
 * There is no {@code position} field: the API contract is the order of the
 * array, and the service renumbers {@code 0..n-1} from it on every write.
 * Exposing the column would invite a client to send positions that disagree with
 * the array order, and then something has to decide which wins.
 *
 * @param mediaId an already-uploaded media id. Media is uploaded first and
 *                independently, then referenced here
 * @param altText optional description for screen readers
 */
public record RecipePictureRequest(

        @NotBlank
        String mediaId,

        @Size(max = 500)
        String altText
) {
}
