package ilenreste.unpeu.recettesback.repositories;

import ilenreste.unpeu.recettesback.entities.PasswordResetTokenEntity;
import ilenreste.unpeu.recettesback.entities.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetTokenEntity, String> {

    Optional<PasswordResetTokenEntity> findByTokenHash(String tokenHash);

    void deleteAllByUser(UserEntity user);
}
