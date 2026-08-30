package ilenreste.unpeu.recettesback.services;

import ilenreste.unpeu.recettesback.entities.UserEntity;
import ilenreste.unpeu.recettesback.models.users.requests.ResetPasswordRequest;
import ilenreste.unpeu.recettesback.models.users.requests.UpdateUserRequest;
import ilenreste.unpeu.recettesback.repositories.UsersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock
    private UsersRepository usersRepository;
    @Mock
    private PasswordResetTokenService tokenService;
    @Mock
    private MailService mailService;
    @Mock
    private UserService userService;

    private PasswordResetService passwordResetService;

    @BeforeEach
    void setUp() {
        passwordResetService = new PasswordResetService(usersRepository, tokenService, mailService, userService);
    }

    @Test
    void requestReset_issuesTokenAndSendsMail_whenEmailIsRegistered() {
        UserEntity user = new UserEntity();
        user.setId("user-1");
        user.setEmail("jane@example.com");
        when(usersRepository.findByEmail("jane@example.com")).thenReturn(Optional.of(user));
        when(tokenService.issueToken(user)).thenReturn("raw-token");

        passwordResetService.requestReset("jane@example.com");

        verify(mailService).sendPasswordResetEmail("jane@example.com", "raw-token");
    }

    @Test
    void requestReset_doesNothingObservable_whenEmailIsUnknown() {
        when(usersRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatCode(() -> passwordResetService.requestReset("ghost@example.com"))
                .doesNotThrowAnyException();

        verifyNoInteractions(tokenService, mailService);
    }

    @Test
    void resetPassword_updatesPassword_whenTokenIsValid() {
        UserEntity user = new UserEntity();
        user.setId("user-1");
        user.setEmail("jane@example.com");
        when(usersRepository.findByEmail("jane@example.com")).thenReturn(Optional.of(user));

        ResetPasswordRequest request = new ResetPasswordRequest("jane@example.com", "raw-token", "NewPassword123");

        passwordResetService.resetPassword(request);

        verify(tokenService).consumeToken(user, "raw-token");

        ArgumentCaptor<UpdateUserRequest> captor = ArgumentCaptor.forClass(UpdateUserRequest.class);
        verify(userService).updateUser(eq(user), captor.capture());
        assertThat(captor.getValue().password()).contains("NewPassword123");
        assertThat(captor.getValue().email()).isEmpty();
        assertThat(captor.getValue().username()).isEmpty();
    }

    @Test
    void resetPassword_throws_whenEmailIsUnknown() {
        when(usersRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());
        ResetPasswordRequest request = new ResetPasswordRequest("ghost@example.com", "raw-token", "NewPassword123");

        assertThatThrownBy(() -> passwordResetService.resetPassword(request))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(tokenService, userService);
    }

    @Test
    void resetPassword_doesNotUpdatePassword_whenTokenIsInvalid() {
        UserEntity user = new UserEntity();
        user.setId("user-1");
        user.setEmail("jane@example.com");
        when(usersRepository.findByEmail("jane@example.com")).thenReturn(Optional.of(user));
        doThrow(new IllegalStateException("Invalid or expired password reset token"))
                .when(tokenService).consumeToken(user, "bad-token");

        ResetPasswordRequest request = new ResetPasswordRequest("jane@example.com", "bad-token", "NewPassword123");

        assertThatThrownBy(() -> passwordResetService.resetPassword(request))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(userService);
    }
}
