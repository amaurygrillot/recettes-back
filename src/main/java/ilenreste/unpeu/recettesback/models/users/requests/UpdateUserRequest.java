package ilenreste.unpeu.recettesback.models.users.requests;

import jakarta.validation.constraints.Size;

import java.util.Optional;

public record UpdateUserRequest(

        Optional<@Size(min = 3, max = 50) String> username,
        Optional<@Size(min = 8, max = 50) String> password,
        Optional<@Size(min = 3, max = 50) String> email,
        Optional<String> firstname,
        Optional<String> lastname
) {
}

