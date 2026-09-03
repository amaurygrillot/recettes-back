package ilenreste.unpeu.recettesback.services.users;

import ilenreste.unpeu.recettesback.entities.users.PasswordResetTokenEntity;
import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.repositories.users.PasswordResetTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Issues and consumes single-use password reset tokens.
 * <p>
 * A token is a random opaque string, never derived from any user data. Only its
 * SHA-256 hash is ever persisted, so a leaked database backup cannot be used to
 * reset an account. A row is deleted as soon as it is looked up for consumption
 * (valid or not), so a token can only ever be used once.
 */
@Service
public class PasswordResetTokenService {

    private static final int TOKEN_BYTE_LENGTH = 32;
    private static final String HASH_ALGORITHM = "SHA-256";

    private final PasswordResetTokenRepository tokenRepository;
    private final Duration tokenTtl;
    private final SecureRandom secureRandom = new SecureRandom();

    public PasswordResetTokenService(
            PasswordResetTokenRepository tokenRepository,
            @Value("${app.password-reset.token-expiry-minutes:15}") long tokenExpiryMinutes
    ) {
        this.tokenRepository = tokenRepository;
        this.tokenTtl = Duration.ofMinutes(tokenExpiryMinutes);
    }

    /**
     * Issues a new token for the given user, invalidating any token previously
     * issued to them. Returns the raw token: this is the only time it exists
     * in plaintext outside the caller's memory.
     */
    @Transactional
    public String issueToken(UserEntity user) {
        tokenRepository.deleteAllByUser(user);

        byte[] randomBytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        PasswordResetTokenEntity entity = new PasswordResetTokenEntity();
        entity.setTokenHash(hash(rawToken));
        entity.setUser(user);
        entity.setCreatedAt(Instant.now());
        entity.setExpiresAt(Instant.now().plus(tokenTtl));
        tokenRepository.save(entity);

        return rawToken;
    }

    /**
     * Validates a raw token for the given user and consumes it (it cannot be
     * used again after this call, whether or not it was valid).
     *
     * @throws IllegalStateException if the token is unknown, belongs to a
     *                               different user, or has expired
     */
    @Transactional
    public void consumeToken(UserEntity user, String rawToken) {
        PasswordResetTokenEntity entity = tokenRepository.findByTokenHash(hash(rawToken))
                .filter(t -> t.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new IllegalStateException("Invalid or expired password reset token"));

        tokenRepository.delete(entity);

        if (entity.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalStateException("Invalid or expired password reset token");
        }
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashBytes = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
