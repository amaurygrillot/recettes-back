package ilenreste.unpeu.recettesback.models;

import java.util.List;

public record AuthenticatedUser(
        String username,
        List<String> roles
) {}

