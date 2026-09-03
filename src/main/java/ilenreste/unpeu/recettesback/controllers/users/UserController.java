package ilenreste.unpeu.recettesback.controllers.users;

import ilenreste.unpeu.recettesback.models.users.requests.CreateUserRequest;
import ilenreste.unpeu.recettesback.models.users.requests.ResetPasswordRequest;
import ilenreste.unpeu.recettesback.models.users.requests.UpdateUserRequest;
import ilenreste.unpeu.recettesback.services.users.PasswordResetService;
import ilenreste.unpeu.recettesback.services.users.UserService;
import jakarta.validation.Valid;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Log4j2
@RequestMapping("/users")
@RestController
public class UserController {

    private final UserService userService;
    private final PasswordResetService passwordResetService;

    public UserController(UserService userService, PasswordResetService passwordResetService) {
        this.userService = userService;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/create")
    public ResponseEntity<Void> createUser(@Valid @RequestBody CreateUserRequest request) {
        log.info("Creating a new user account");
        userService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PutMapping("/update")
    public ResponseEntity<Void> updateUser(@AuthenticationPrincipal Jwt jwt,
                                           @Valid @RequestBody UpdateUserRequest request) {
        String userId = (String) jwt.getClaims().get("userId");
        log.info("Updating user {}", userId);
        userService.updateUser(userId, request);
        return ResponseEntity.ok().build();
    }

    /**
     * Completes the "forgot password" flow: sets a new password if the token
     * from the reset email is valid for the given account.
     * <p>
     * Every way this can fail answers identically — see
     * {@link UserExceptionHandler}.
     */
    @PutMapping("/reinit-password")
    public ResponseEntity<Void> reinitPassword(@Valid @RequestBody ResetPasswordRequest request) {
        log.info("Applying a password reset");
        passwordResetService.resetPassword(request);
        return ResponseEntity.ok().build();
    }
}
