package ilenreste.unpeu.recettesback.services.users;

import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.exceptions.PasswordResetFailedException;
import ilenreste.unpeu.recettesback.models.users.requests.ResetPasswordRequest;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the "forgot password" flow: requesting a reset email, then
 * applying the new password once a valid token comes back.
 */
@Service
public class PasswordResetService {

    private final UsersRepository usersRepository;
    private final PasswordResetTokenService tokenService;
    private final MailService mailService;
    private final UserService userService;

    public PasswordResetService(
            UsersRepository usersRepository, PasswordResetTokenService tokenService,
            MailService mailService, UserService userService
    ) {
        this.usersRepository = usersRepository;
        this.tokenService = tokenService;
        this.mailService = mailService;
        this.userService = userService;
    }

    /**
     * Requests a password reset for the given email. Deliberately does nothing
     * observable when the email is not registered, and never throws for that
     * case: the caller must respond identically either way, otherwise the
     * endpoint becomes an oracle for discovering registered email addresses.
     */
    public void requestReset(String email) {
        usersRepository.findByEmail(email).ifPresent(user -> {
            String token = tokenService.issueToken(user);
            mailService.sendPasswordResetEmail(user.getEmail(), token);
        });
    }

    /**
     * Applies a new password if, and only if, the token is valid for the given
     * email. Any failure (unknown email, unknown/expired/foreign token) is
     * reported the same way so it can't be used to enumerate accounts.
     * <p>
     * Transactional so that consuming the token and applying the new password
     * are all-or-nothing: without this, a failure in the password update after
     * the token was already deleted would burn the user's one reset token
     * without ever changing their password.
     * <p>
     * Calls {@link UserService#applyNewPassword} and not
     * {@code UserService.updateUser}: that method requires the caller's
     * <em>current</em> password before it will change a password, which is
     * precisely what somebody in this flow does not have. The token consumed
     * one line above is the proof of identity here, and it has already been
     * checked. Routing a reset through the self-service method would either lock
     * out everyone who has forgotten their password or force that method to
     * accept "no current password" as valid, which would remove the guard for
     * both callers.
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        UserEntity user = usersRepository.findByEmail(request.email())
                .orElseThrow(() -> new PasswordResetFailedException("Invalid password reset request"));

        tokenService.consumeToken(user, request.token());

        userService.applyNewPassword(user, request.newPassword());
    }
}
