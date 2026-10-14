package ilenreste.unpeu.recettesback.models.users.requests;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import java.util.Optional;

/**
 * A partial update of the <strong>calling</strong> account. Every field is
 * optional; an absent one is left alone.
 *
 * @param currentPassword proof that the caller is the account holder and not
 *                        merely someone holding their token. Required when
 *                        {@code password} or {@code email} is present, and
 *                        ignored otherwise — see
 *                        {@code UserService.updateUser} for why those two
 *                        fields and no others.
 *                        <p>
 *                        This is <strong>not</strong> how a forgotten password
 *                        is changed: somebody who has forgotten it cannot
 *                        supply it. That flow is
 *                        {@code PUT /users/reinit-password}, where the emailed
 *                        one-time token is the proof instead, and it does not
 *                        come through this record at all.
 * @param email           {@code @Email} rather than a bare length check. A
 *                        stored address that is not an address silently breaks
 *                        {@code POST /auth/reinit-password}, which is the one
 *                        way back into a locked-out account — the failure
 *                        surfaces as "no reset mail ever arrived", nowhere near
 *                        the request that caused it. 254 is the RFC 5321 limit
 *                        and fits the existing column.
 */
public record UpdateUserRequest(

        Optional<@Size(min = 3, max = 50) String> username,
        Optional<@Size(min = 8, max = 50) String> password,
        Optional<@Email @Size(max = 254) String> email,
        Optional<String> firstname,
        Optional<String> lastname,
        Optional<String> currentPassword
) {
}
