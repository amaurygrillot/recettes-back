package ilenreste.unpeu.recettesback.models.users.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(

        @NotBlank
        @Size(min = 3, max = 50)
        String username,
        @NotBlank
        @Size(min = 8, max = 50)
        String password,
        @NotBlank
        @Size(min = 3, max = 50)
        String email,
        String firstname,
        String lastname
) {
}

