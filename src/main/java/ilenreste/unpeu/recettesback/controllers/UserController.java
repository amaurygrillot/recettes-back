package ilenreste.unpeu.recettesback.controllers;

import ilenreste.unpeu.recettesback.models.users.requests.CreateUserRequest;
import ilenreste.unpeu.recettesback.models.users.requests.UpdateUserRequest;
import ilenreste.unpeu.recettesback.services.UserService;
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

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/create")
    public ResponseEntity<Void> createUser(@Valid @RequestBody CreateUserRequest request) {
        try {
            userService.createUser(request);
        } catch (IllegalStateException illegalStateException) {
            log.error("Bad request for create user", illegalStateException);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (Exception exception) {
            log.error("Error when trying to create user", exception);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PutMapping("/update")
    public ResponseEntity<Void> updateUser(@AuthenticationPrincipal Jwt jwt,
                                           @Valid @RequestBody UpdateUserRequest request) {
        try {
            String userId = (String) jwt.getClaims().get("userId");
            userService.updateUser(userId, request);
        } catch (IllegalStateException illegalStateException) {
            log.error("Bad request for update user", illegalStateException);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (Exception exception) {
            log.error("Error when trying to update user", exception);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        return ResponseEntity.ok().build();
    }
}
