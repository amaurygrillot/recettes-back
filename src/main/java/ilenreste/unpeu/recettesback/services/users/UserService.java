package ilenreste.unpeu.recettesback.services.users;

import ilenreste.unpeu.recettesback.entities.users.RoleEntity;
import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.entities.users.UserRolesEntity;
import ilenreste.unpeu.recettesback.exceptions.ResourceConflictException;
import ilenreste.unpeu.recettesback.exceptions.ResourceNotFoundException;
import ilenreste.unpeu.recettesback.models.users.requests.CreateUserRequest;
import ilenreste.unpeu.recettesback.models.users.requests.UpdateUserRequest;
import ilenreste.unpeu.recettesback.repositories.users.RolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UserRolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
import org.jspecify.annotations.NullMarked;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@NullMarked
public class UserService {

    private final UsersRepository usersRepository;
    private final RolesRepository rolesRepository;
    private final UserRolesRepository userRolesRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(
            UsersRepository usersRepository, RolesRepository rolesRepository, UserRolesRepository userRolesRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.usersRepository = usersRepository;
        this.rolesRepository = rolesRepository;
        this.userRolesRepository = userRolesRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public void createUser(CreateUserRequest request) {

        if (usersRepository.existsByUsername(request.username())) {
            throw new ResourceConflictException("Username already exists");
        }

        String passwordHash = passwordEncoder.encode(request.password());
        UserEntity user = new UserEntity();
        user.setUsername(request.username());
        user.setPassword(passwordHash);
        user.setEnabled(true);
        user.setEmail(request.email());
        user.setFirstname(request.firstname());
        user.setLastname(request.lastname());

        UserEntity userEntity = usersRepository.save(user);
        RoleEntity roleEntity = rolesRepository.findByNameEqualsIgnoreCase("USER");
        UserRolesEntity userRolesEntity = new UserRolesEntity();
        userRolesEntity.setUser(userEntity);
        userRolesEntity.setRole(roleEntity);
        userRolesRepository.save(userRolesEntity);
    }

    public void updateUser(String userId, UpdateUserRequest request) {
        UserEntity user = usersRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("user", userId));
        updateUser(user, request);
    }

    /**
     * Same as {@link #updateUser(String, UpdateUserRequest)} but for a caller
     * that already holds the {@link UserEntity} (e.g. after looking it up by
     * some other key), sparing a redundant fetch-by-id.
     */
    public void updateUser(UserEntity user, UpdateUserRequest request) {
        request.email().ifPresent(user::setEmail);
        request.firstname().ifPresent(user::setFirstname);
        request.lastname().ifPresent(user::setLastname);
        request.username().ifPresent(user::setUsername);
        request.password().ifPresent(password -> user.setPassword(passwordEncoder.encode(password)));
        usersRepository.save(user);
    }
}

