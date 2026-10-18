package ilenreste.unpeu.recettesback.controllers.users;

import ilenreste.unpeu.recettesback.api.UsersApi;
import ilenreste.unpeu.recettesback.exceptions.ForbiddenOperationException;
import ilenreste.unpeu.recettesback.exceptions.handlers.UserExceptionHandler;
import ilenreste.unpeu.recettesback.models.users.requests.CreateUserRequest;
import ilenreste.unpeu.recettesback.models.users.requests.ResetPasswordRequest;
import ilenreste.unpeu.recettesback.models.users.requests.UpdateUserRequest;
import ilenreste.unpeu.recettesback.models.users.responses.UserResponse;
import ilenreste.unpeu.recettesback.services.users.CurrentUserService;
import ilenreste.unpeu.recettesback.services.users.PasswordResetService;
import ilenreste.unpeu.recettesback.services.users.UserService;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Accounts.
 * <p>
 * Both writes answer with the stored account rather than an empty body. A bare
 * {@code 201} only ever said "nothing threw": the client got no id to follow, no
 * {@code Location} to fetch, and no way to see what was actually persisted. The
 * failure cases are typed exceptions mapped centrally — 409 for a taken username,
 * 404 for an account that no longer exists — so there is still nothing to check
 * here by hand.
 * <p>
 * The routes, parameters, status codes and security of every method here are
 * declared in {@code openapi.yaml} and reach this class through the generated
 * {@link UsersApi}; see {@code docs/openapi.md}.
 */
@Log4j2
@RestController
public class UserController implements UsersApi {

    private final UserService userService;
    private final PasswordResetService passwordResetService;
    private final CurrentUserService currentUserService;

    public UserController(UserService userService, PasswordResetService passwordResetService,
                          CurrentUserService currentUserService) {
        this.userService = userService;
        this.passwordResetService = passwordResetService;
        this.currentUserService = currentUserService;
    }

    @Override
    public ResponseEntity<UserResponse> createUser(CreateUserRequest request) {
        log.info("Creating a new user account");
        UserResponse created = userService.createUser(request);
        // Here rather than in the service: this line runs after the transaction has committed.
        log.info("Created user {}", created.id());
        return ResponseEntity.created(URI.create("/users/" + created.id())).body(created);
    }

    /**
     * Updates <strong>the calling account</strong> — there is no id in the path,
     * and the body cannot name one.
     * <p>
     * The caller's id comes from {@link CurrentUserService}, the single answer to
     * "who is calling", rather than from a hand-read {@code userId} claim on an
     * {@code @AuthenticationPrincipal Jwt}. The claim is the same either way; what
     * differs is that the cast and the anonymous-token trap live in one place
     * instead of being re-derived per controller. See that class for why a null
     * check is the wrong guard.
     */
    @Override
    public ResponseEntity<UserResponse> updateUser(UpdateUserRequest request) {
        String userId = currentUserService.currentUserId()
                .orElseThrow(() -> new ForbiddenOperationException(
                        "You must be signed in to update your account."));
        log.info("Updating user {}", userId);
        return ResponseEntity.ok(userService.updateUser(userId, request));
    }

    /**
     * Completes the "forgot password" flow: sets a new password if the token
     * from the reset email is valid for the given account.
     * <p>
     * Every way this can fail answers identically — see
     * {@link UserExceptionHandler}.
     */
    @Override
    public ResponseEntity<Void> resetPassword(ResetPasswordRequest request) {
        log.info("Applying a password reset");
        passwordResetService.resetPassword(request);
        return ResponseEntity.ok().build();
    }
}
