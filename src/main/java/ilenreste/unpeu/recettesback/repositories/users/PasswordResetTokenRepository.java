package ilenreste.unpeu.recettesback.repositories.users;

import ilenreste.unpeu.recettesback.entities.users.PasswordResetTokenEntity;
import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetTokenEntity, String> {

    Optional<PasswordResetTokenEntity> findByTokenHash(String tokenHash);

    void deleteAllByUser(UserEntity user);
}
