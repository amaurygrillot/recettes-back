package ilenreste.unpeu.recettesback.services.users;

import ilenreste.unpeu.recettesback.entities.users.RoleEntity;
import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.entities.users.UserRolesEntity;
import ilenreste.unpeu.recettesback.exceptions.ResourceConflictException;
import ilenreste.unpeu.recettesback.exceptions.ResourceNotFoundException;
import ilenreste.unpeu.recettesback.mappers.users.UserMapper;
import ilenreste.unpeu.recettesback.models.users.requests.CreateUserRequest;
import ilenreste.unpeu.recettesback.models.users.requests.UpdateUserRequest;
import ilenreste.unpeu.recettesback.models.users.responses.UserResponse;
import ilenreste.unpeu.recettesback.repositories.users.RolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UserRolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
import lombok.extern.log4j.Log4j2;
import org.jspecify.annotations.NullMarked;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accounts.
 * <p>
 * Both write methods return the stored account rather than {@code void}. That is
 * not cosmetic: a {@code void} create forces the controller above to answer
 * {@code 201} on the strength of "nothing threw", with no id to put in a
 * {@code Location} header and no way for a client to see what was actually
 * persisted. Returning the mapped row makes the success path carry its own
 * evidence, and every failure path below throws.
 */
@Log4j2
@Service
@NullMarked
public class UserService {

    /** Granted to every new account. Must exist; see {@link #requireRole}. */
    private static final String DEFAULT_ROLE = "USER";

    private final UsersRepository usersRepository;
    private final RolesRepository rolesRepository;
    private final UserRolesRepository userRolesRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper mapper;

    public UserService(
            UsersRepository usersRepository, RolesRepository rolesRepository, UserRolesRepository userRolesRepository,
            PasswordEncoder passwordEncoder, UserMapper mapper
    ) {
        this.usersRepository = usersRepository;
        this.rolesRepository = rolesRepository;
        this.userRolesRepository = userRolesRepository;
        this.passwordEncoder = passwordEncoder;
        this.mapper = mapper;
    }

    /**
     * Registers an account and grants it {@code USER}, in one transaction — an
     * account without its role row can sign in and then fail every
     * {@code hasRole("USER")} rule, which looks like a permissions bug rather
     * than a half-finished insert.
     *
     * @return the stored account, including the generated id and the role it was granted
     * @throws ResourceConflictException if the username is taken, as a 409 rather
     *                                   than letting the unique constraint surface
     *                                   as an opaque failure
     */
    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        if (usersRepository.existsByUsername(request.username())) {
            log.info("Refusing to create an account: the username is already taken");
            throw new ResourceConflictException("Username already exists");
        }

        // Looked up before the insert, so a missing role row fails with a message naming the cause
        // rather than as a NOT NULL violation on user_roles.role_id two statements later.
        RoleEntity role = requireRole(DEFAULT_ROLE);

        UserEntity user = new UserEntity();
        user.setUsername(request.username());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setEnabled(true);
        user.setEmail(request.email());
        user.setFirstname(request.firstname());
        user.setLastname(request.lastname());

        UserEntity saved = usersRepository.save(user);

        // Written through UserRolesEntity, not user.getRoles().add(...): user_roles is mapped both
        // as the @ManyToMany join table and as an entity with its own id column, so a @ManyToMany
        // insert leaves that NOT NULL id null and fails.
        UserRolesEntity link = new UserRolesEntity();
        link.setUser(saved);
        link.setRole(role);
        userRolesRepository.save(link);
        saved.getRoles().add(role);

        log.info("Created user {} with role {}", saved.getId(), DEFAULT_ROLE);
        return mapper.toResponse(saved);
    }

    /**
     * @return the account as stored after the update
     * @throws ResourceNotFoundException if the id names no account — a 404 rather
     *                                   than a silent no-op, which would tell a
     *                                   caller their change was applied when
     *                                   there was nothing to apply it to
     */
    @Transactional
    public UserResponse updateUser(String userId, UpdateUserRequest request) {
        UserEntity user = usersRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("user", userId));
        return updateUser(user, request);
    }

    /**
     * Same as {@link #updateUser(String, UpdateUserRequest)} but for a caller
     * that already holds the {@link UserEntity} (e.g. after looking it up by
     * some other key), sparing a redundant fetch-by-id.
     */
    @Transactional
    public UserResponse updateUser(UserEntity user, UpdateUserRequest request) {
        request.email().ifPresent(user::setEmail);
        request.firstname().ifPresent(user::setFirstname);
        request.lastname().ifPresent(user::setLastname);
        request.username().ifPresent(user::setUsername);
        request.password().ifPresent(password -> user.setPassword(passwordEncoder.encode(password)));

        UserEntity saved = usersRepository.save(user);
        log.info("Updated user {}", saved.getId());
        return mapper.toResponse(saved);
    }

    /**
     * The {@code roles} table is seeded by {@code ReferenceDataSeeder} at startup,
     * so a missing row here means the application is misconfigured rather than
     * that the request was wrong — hence a 500, logged with the role name, and not
     * a 400 blaming the caller for something they cannot fix.
     */
    private RoleEntity requireRole(String roleName) {
        RoleEntity role = rolesRepository.findByNameEqualsIgnoreCase(roleName);
        if (role == null) {
            log.error("The '{}' role is missing from the roles table; no account can be created "
                    + "until it is seeded", roleName);
            throw new IllegalStateException("The '%s' role is missing.".formatted(roleName));
        }
        return role;
    }
}
