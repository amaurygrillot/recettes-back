package ilenreste.unpeu.recettesback.services;

import ilenreste.unpeu.recettesback.entities.UserEntity;
import ilenreste.unpeu.recettesback.models.users.requests.ResetPasswordRequest;
import ilenreste.unpeu.recettesback.models.users.requests.UpdateUserRequest;
import ilenreste.unpeu.recettesback.repositories.UsersRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

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
     */
    public void resetPassword(ResetPasswordRequest request) {
        UserEntity user = usersRepository.findByEmail(request.email())
                .orElseThrow(() -> new IllegalStateException("Invalid password reset request"));

        tokenService.consumeToken(user, request.token());

        userService.updateUser(user.getId(), new UpdateUserRequest(
                Optional.empty(),
                Optional.of(request.newPassword()),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        ));
    }
}
