package ilenreste.unpeu.recettesback.models.users.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(

        @NotBlank
        @Size(min = 3, max = 50)
        String email,
        @NotBlank
        String token,
        @NotBlank
        @Size(min = 8, max = 50)
        String newPassword
) {
}
