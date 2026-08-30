package ilenreste.unpeu.recettesback.repositories.users;

import ilenreste.unpeu.recettesback.entities.users.UserRolesEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserRolesRepository extends JpaRepository<UserRolesEntity, UUID> {

}
