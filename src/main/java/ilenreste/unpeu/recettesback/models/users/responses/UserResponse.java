package ilenreste.unpeu.recettesback.models.users.responses;

import java.util.List;

/**
 * An account as the API exposes it.
 * <p>
 * Returned by {@code POST /users/create} and {@code PUT /users/update}, which
 * both used to answer with an empty body. That was the actual gap behind "what if
 * the user could not be created?": a bare {@code 201} says only that nothing
 * threw, so a client had no id to follow, no way to see the stored username, and
 * no way to tell a create that worked from one that quietly did half its job.
 * <p>
 * Note what never crosses: the password hash, obviously, but also
 * {@code enabled} — whether an account is disabled is a moderation decision, and
 * echoing it back to the account holder invites "why?" without giving the API
 * anything to say. The login endpoint is where a disabled account is told, once,
 * that it cannot sign in.
 *
 * @param roles role names without the {@code ROLE_} prefix, matching the
 *              {@code roles} claim in the JWT, so a frontend has one spelling to
 *              reason about rather than two
 */
public record UserResponse(
        String id,
        String username,
        String email,
        String firstname,
        String lastname,
        List<String> roles
) {
}
