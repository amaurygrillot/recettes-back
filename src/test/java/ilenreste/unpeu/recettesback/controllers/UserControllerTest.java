package ilenreste.unpeu.recettesback.controllers;

import ilenreste.unpeu.recettesback.services.PasswordResetService;
import ilenreste.unpeu.recettesback.services.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserControllerTest {

    private PasswordResetService passwordResetService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        passwordResetService = mock(PasswordResetService.class);
        UserController controller = new UserController(mock(UserService.class), passwordResetService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
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
