package ilenreste.unpeu.recettesback.services;

import ilenreste.unpeu.recettesback.entities.PasswordResetTokenEntity;
import ilenreste.unpeu.recettesback.entities.UserEntity;
import ilenreste.unpeu.recettesback.repositories.PasswordResetTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordResetTokenServiceTest {

    @Mock
    private PasswordResetTokenRepository tokenRepository;

    private PasswordResetTokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new PasswordResetTokenService(tokenRepository, 15);
    }

    @Test
    void issueToken_deletesPreviousTokensAndPersistsOnlyTheHash() {
        UserEntity user = new UserEntity();
        user.setId("user-1");

        String rawToken = tokenService.issueToken(user);

        assertThat(rawToken).isNotBlank();

        InOrder inOrder = inOrder(tokenRepository);
        inOrder.verify(tokenRepository).deleteAllByUser(user);

        ArgumentCaptor<PasswordResetTokenEntity> captor = ArgumentCaptor.forClass(PasswordResetTokenEntity.class);
        inOrder.verify(tokenRepository).save(captor.capture());

        PasswordResetTokenEntity saved = captor.getValue();
        assertThat(saved.getUser()).isEqualTo(user);
        assertThat(saved.getTokenHash()).isNotEqualTo(rawToken);
        assertThat(saved.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void consumeToken_succeeds_forFreshlyIssuedToken() {
        UserEntity user = new UserEntity();
        user.setId("user-1");

        String rawToken = tokenService.issueToken(user);
        ArgumentCaptor<PasswordResetTokenEntity> captor = ArgumentCaptor.forClass(PasswordResetTokenEntity.class);
        verify(tokenRepository).save(captor.capture());
        PasswordResetTokenEntity persisted = captor.getValue();

        when(tokenRepository.findByTokenHash(persisted.getTokenHash())).thenReturn(Optional.of(persisted));

        assertThatCode(() -> tokenService.consumeToken(user, rawToken)).doesNotThrowAnyException();

        verify(tokenRepository).delete(persisted);
    }

    @Test
    void consumeToken_throws_forUnknownToken() {
        UserEntity user = new UserEntity();
        user.setId("user-1");
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tokenService.consumeToken(user, "not-a-real-token"))
                .isInstanceOf(IllegalStateException.class);

        verify(tokenRepository, never()).delete(any());
    }

    @Test
    void consumeToken_throwsAndStillConsumes_forExpiredToken() {
        UserEntity user = new UserEntity();
        user.setId("user-1");

        PasswordResetTokenEntity expired = new PasswordResetTokenEntity();
        expired.setId("token-1");
        expired.setUser(user);
        expired.setTokenHash("hash");
        expired.setCreatedAt(Instant.now().minus(Duration.ofHours(2)));
        expired.setExpiresAt(Instant.now().minus(Duration.ofHours(1)));

        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> tokenService.consumeToken(user, "some-token"))
                .isInstanceOf(IllegalStateException.class);

        // Single-use even on a failed (expired) attempt: the row must not survive to be retried.
        verify(tokenRepository).delete(expired);
    }

    @Test
    void consumeToken_throws_whenTokenBelongsToAnotherUser() {
        UserEntity owner = new UserEntity();
        owner.setId("user-1");
        UserEntity someoneElse = new UserEntity();
        someoneElse.setId("user-2");

        PasswordResetTokenEntity entity = new PasswordResetTokenEntity();
        entity.setId("token-1");
        entity.setUser(owner);
        entity.setTokenHash("hash");
        entity.setExpiresAt(Instant.now().plus(Duration.ofMinutes(10)));

        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> tokenService.consumeToken(someoneElse, "some-token"))
                .isInstanceOf(IllegalStateException.class);

        verify(tokenRepository, never()).delete(any());
    }
}
