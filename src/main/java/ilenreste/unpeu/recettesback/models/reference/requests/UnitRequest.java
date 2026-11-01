package ilenreste.unpeu.recettesback.models.reference.requests;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create or update a unit of measure.
 *
 * @param name         the full name, e.g. "cuillere a soupe"
 * @param abbreviation the short form a recipe line renders with, e.g. "c. a s.".
 *                     Optional: not every unit has a sensible abbreviation
 *                     ("pincee" is already short)
 * @param hidden       true when a recipe line must not render this unit ("piece": "3 oeufs").
 *                     Optional, false when omitted
 */
public record UnitRequest(

        @NotBlank
        @Size(min = 1, max = 100)
        String name,

        @JsonProperty(required = false)
        @Size(max = 20)
        String abbreviation,

        @JsonProperty(required = false)
        Boolean hidden
) {
}
