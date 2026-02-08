package ilenreste.unpeu.recettesback.services;

import ilenreste.unpeu.recettesback.entities.RoleEntity;
import ilenreste.unpeu.recettesback.entities.UserEntity;
import ilenreste.unpeu.recettesback.entities.UserRolesEntity;
import ilenreste.unpeu.recettesback.models.users.requests.CreateUserRequest;
import ilenreste.unpeu.recettesback.models.users.requests.UpdateUserRequest;
import ilenreste.unpeu.recettesback.repositories.RolesRepository;
import ilenreste.unpeu.recettesback.repositories.UserRolesRepository;
import ilenreste.unpeu.recettesback.repositories.UsersRepository;
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
            throw new IllegalStateException("Username already exists");
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
                .orElseThrow(() -> new IllegalStateException("User doesn't exist"));
        request.email().ifPresent(user::setEmail);
        request.firstname().ifPresent(user::setFirstname);
        request.lastname().ifPresent(user::setLastname);
        request.username().ifPresent(user::setUsername);
        request.password().ifPresent(password -> user.setPassword(passwordEncoder.encode(password)));
        usersRepository.save(user);
    }
}

