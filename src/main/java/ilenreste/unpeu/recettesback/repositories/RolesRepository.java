package ilenreste.unpeu.recettesback.repositories;

import ilenreste.unpeu.recettesback.entities.RoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RolesRepository extends JpaRepository<RoleEntity, UUID> {

    RoleEntity findByNameEqualsIgnoreCase(String name);

}
