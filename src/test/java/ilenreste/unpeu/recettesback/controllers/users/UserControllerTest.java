package ilenreste.unpeu.recettesback.controllers.users;

import ilenreste.unpeu.recettesback.services.users.PasswordResetService;
import ilenreste.unpeu.recettesback.services.users.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserControllerTest {

    private PasswordResetService passwordResetService;
    private UserService userService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        passwordResetService = mock(PasswordResetService.class);
        userService = mock(UserService.class);
        UserController controller = new UserController(userService, passwordResetService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String userId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("userId", userId)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(jwt, null));
    }

    @Test
    void createUser_returns201_whenRequestSucceeds() throws Exception {
        mockMvc.perform(post("/users/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"jane","password":"Password123","email":"jane@example.com","firstname":"Jane","lastname":"Doe"}
                                """))
                .andExpect(status().isCreated());

        verify(userService).createUser(any());
    }

    @Test
    void createUser_returns400_whenUsernameAlreadyExists() throws Exception {
        doThrow(new IllegalStateException("Username already exists")).when(userService).createUser(any());

        mockMvc.perform(post("/users/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"jane","password":"Password123","email":"jane@example.com","firstname":"Jane","lastname":"Doe"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createUser_returns500_whenServiceThrowsUnexpectedly() throws Exception {
        doThrow(new RuntimeException("db down")).when(userService).createUser(any());

        mockMvc.perform(post("/users/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"jane","password":"Password123","email":"jane@example.com","firstname":"Jane","lastname":"Doe"}
                                """))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void createUser_returns400_whenPayloadFailsValidation() throws Exception {
        mockMvc.perform(post("/users/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"","password":"Password123","email":"jane@example.com"}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(userService);
    }

    @Test
    void updateUser_returns200_whenRequestSucceeds() throws Exception {
        authenticateAs("user-1");

        mockMvc.perform(put("/users/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstname":"Janet"}
                                """))
                .andExpect(status().isOk());

        verify(userService).updateUser(eq("user-1"), any());
    }

    @Test
    void updateUser_returns400_whenUserDoesNotExist() throws Exception {
        authenticateAs("missing-id");
        doThrow(new IllegalStateException("User doesn't exist")).when(userService).updateUser(eq("missing-id"), any());

        mockMvc.perform(put("/users/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstname":"Janet"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateUser_returns500_whenServiceThrowsUnexpectedly() throws Exception {
        authenticateAs("user-1");
        doThrow(new RuntimeException("db down")).when(userService).updateUser(eq("user-1"), any());

        mockMvc.perform(put("/users/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstname":"Janet"}
                                """))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void updateUser_returns400_whenPayloadFailsValidation() throws Exception {
        authenticateAs("user-1");

        mockMvc.perform(put("/users/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"short"}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(userService);
    }

    @Test
    void reinitPassword_returnsOk_whenTokenIsValid() throws Exception {
        mockMvc.perform(put("/users/reinit-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"jane@example.com","token":"abc123","newPassword":"NewPassword123"}
                                """))
                .andExpect(status().isOk());

        verify(passwordResetService).resetPassword(any());
    }

    @Test
    void reinitPassword_returns400_whenTokenIsInvalid() throws Exception {
        doThrow(new IllegalStateException("Invalid password reset request"))
                .when(passwordResetService).resetPassword(any());

        mockMvc.perform(put("/users/reinit-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"jane@example.com","token":"wrong","newPassword":"NewPassword123"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reinitPassword_returns400_whenNewPasswordIsTooShort() throws Exception {
        mockMvc.perform(put("/users/reinit-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"jane@example.com","token":"abc123","newPassword":"short"}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(passwordResetService);
    }

    @Test
    void reinitPassword_returns500_whenServiceThrowsUnexpectedly() throws Exception {
        doThrow(new RuntimeException("db down")).when(passwordResetService).resetPassword(any());

        mockMvc.perform(put("/users/reinit-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"jane@example.com","token":"abc123","newPassword":"NewPassword123"}
                                """))
                .andExpect(status().isInternalServerError());
    }
}
