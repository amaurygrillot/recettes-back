package ilenreste.unpeu.recettesback.mappers.users;

import ilenreste.unpeu.recettesback.entities.users.RoleEntity;
import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.models.users.responses.UserResponse;
import org.springframework.stereotype.Component;

import java.util.Collection;
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
        return toResponse(user, user.getRoles());
    }

    /**
     * For an account whose roles are not on the entity yet: {@code UserService.createUser} links them
     * through {@code UserRolesEntity}, and adding them to the managed {@code user.getRoles()} as well
     * would make Hibernate insert the same link a second time, without its {@code id}, at commit.
     */
    public UserResponse toResponse(UserEntity user, Collection<RoleEntity> roles) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getFirstname(),
                user.getLastname(),
                // Sorted so two identical requests answer identically: roles are mapped as a
                // HashSet, which iterates in hash order.
                roles.stream()
                        .map(RoleEntity::getName)
                        .sorted(Comparator.naturalOrder())
                        .toList()
        );
    }
}
