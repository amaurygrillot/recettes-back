package ilenreste.unpeu.recettesback.models.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReinitPasswordRequest(

        @NotBlank
        @Size(min = 3, max = 50)
        String email
) {
}
