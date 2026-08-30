package ilenreste.unpeu.recettesback.controllers;

import ilenreste.unpeu.recettesback.models.users.CustomUserDetails;
import ilenreste.unpeu.recettesback.services.PasswordResetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthenticationControllerTest {

    private PasswordResetService passwordResetService;
    private AuthenticationManager authenticationManager;
    private JwtEncoder jwtEncoder;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        passwordResetService = mock(PasswordResetService.class);
        authenticationManager = mock(AuthenticationManager.class);
        jwtEncoder = mock(JwtEncoder.class);
        AuthenticationController controller = new AuthenticationController(
                authenticationManager, jwtEncoder, passwordResetService
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private Authentication mockAuthentication(String id, boolean authenticated, boolean enabled) {
        CustomUserDetails userDetails = mock(CustomUserDetails.class);
        when(userDetails.getId()).thenReturn(id);
        when(userDetails.getUsername()).thenReturn("jane");
        when(userDetails.isEnabled()).thenReturn(enabled);
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("USER"));
        doReturn(authorities).when(userDetails).getAuthorities();

        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(userDetails);
        when(auth.isAuthenticated()).thenReturn(authenticated);
        return auth;
    }

    @Test
    void login_returnsToken_whenCredentialsAreValid() throws Exception {
        Authentication auth = mockAuthentication("user-1", true, true);
        when(authenticationManager.authenticate(any())).thenReturn(auth);
        Jwt jwt = mock(Jwt.class);
        when(jwt.getTokenValue()).thenReturn("signed-jwt-token");
        when(jwtEncoder.encode(any())).thenReturn(jwt);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"jane","password":"Password123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().string("signed-jwt-token"));
    }

    @Test
    void login_returns401_whenUserIsDisabled() throws Exception {
        Authentication auth = mockAuthentication("user-1", true, false);
        when(authenticationManager.authenticate(any())).thenReturn(auth);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"jane","password":"Password123"}
                                """))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(jwtEncoder);
    }

    @Test
    void login_returns401_whenAuthenticationIsNotAuthenticated() throws Exception {
        Authentication auth = mockAuthentication("user-1", false, true);
        when(authenticationManager.authenticate(any())).thenReturn(auth);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"jane","password":"Password123"}
                                """))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(jwtEncoder);
    }

    @Test
    void login_returns500_whenPrincipalHasNoId() throws Exception {
        Authentication auth = mockAuthentication(null, true, true);
        when(authenticationManager.authenticate(any())).thenReturn(auth);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"jane","password":"Password123"}
                                """))
                .andExpect(status().isInternalServerError());

        verifyNoInteractions(jwtEncoder);
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
