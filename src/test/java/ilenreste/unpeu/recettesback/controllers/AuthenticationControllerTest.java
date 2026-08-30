package ilenreste.unpeu.recettesback.controllers;

import ilenreste.unpeu.recettesback.services.PasswordResetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthenticationControllerTest {

    private PasswordResetService passwordResetService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        passwordResetService = mock(PasswordResetService.class);
        AuthenticationController controller = new AuthenticationController(
                mock(AuthenticationManager.class), mock(JwtEncoder.class), passwordResetService
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void reinitPassword_returnsAccepted_whenRequestSucceeds() throws Exception {
        mockMvc.perform(post("/auth/reinit-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"jane@example.com\"}"))
                .andExpect(status().isAccepted());

        verify(passwordResetService).requestReset("jane@example.com");
    }

    @Test
    void reinitPassword_returns500_whenServiceThrowsUnexpectedly() throws Exception {
        doThrow(new RuntimeException("mail server down")).when(passwordResetService).requestReset(anyString());

        mockMvc.perform(post("/auth/reinit-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"jane@example.com\"}"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void reinitPassword_returns400_whenEmailIsBlank() throws Exception {
        mockMvc.perform(post("/auth/reinit-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(passwordResetService);
    }
}
