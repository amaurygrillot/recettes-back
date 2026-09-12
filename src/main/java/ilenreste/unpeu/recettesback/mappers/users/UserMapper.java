package ilenreste.unpeu.recettesback.mappers.users;

import ilenreste.unpeu.recettesback.entities.users.RoleEntity;
import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.models.users.responses.UserResponse;
import org.springframework.stereotype.Component;

import java.util.Comparator;

/**
 * Entity to response for accounts.
 * <p>
 * Note what does not cross: the password hash, and {@code enabled}. Both are on
 * the entity and neither belongs in a response — see {@link UserResponse}.
 */
@Component
public class UserMapper {

    public UserResponse toResponse(UserEntity user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getFirstname(),
                user.getLastname(),
                // Sorted so two identical requests answer identically: roles are mapped as a
                // HashSet, which iterates in hash order.
                user.getRoles().stream()
                        .map(RoleEntity::getName)
                        .sorted(Comparator.naturalOrder())
                        .toList()
        );
    }
}
