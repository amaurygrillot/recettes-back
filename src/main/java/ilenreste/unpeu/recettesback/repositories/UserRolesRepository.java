package ilenreste.unpeu.recettesback.repositories;

import ilenreste.unpeu.recettesback.entities.UserRolesEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserRolesRepository extends JpaRepository<UserRolesEntity, UUID> {

}
