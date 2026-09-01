package ilenreste.unpeu.recettesback.support;

import ilenreste.unpeu.recettesback.entities.users.RoleEntity;
import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.entities.users.UserRolesEntity;
import ilenreste.unpeu.recettesback.repositories.users.RolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UserRolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A real account in the real database, for tests that drive the application over HTTP.
 * <p>
 * There is no transaction to roll back behind a real HTTP call - the server handles it on its own
 * threads - so every account gets a unique username and is deleted in {@link #close()}.
 * <p>
 * Roles are linked through {@link UserRolesEntity} rather than {@code UserEntity.setRoles(...)},
 * matching what {@code UserService.createUser} does. The {@code user_roles} table is mapped twice -
 * as the @ManyToMany join table and as an entity with its own id column - so Hibernate creates
 * {@code user_roles.id NOT NULL}, which the @ManyToMany insert never populates. Reads still go
 * through the @ManyToMany side, which is why this works at all. See docs/optional-authentication.md.
 */
public final class TestAccount implements AutoCloseable {

    public static final String PASSWORD = "TestPassword123";

    private final UsersRepository usersRepository;
    private final UserRolesRepository userRolesRepository;
    private final UserEntity user;
    private final List<UserRolesEntity> roleLinks = new ArrayList<>();

    private TestAccount(UsersRepository usersRepository, UserRolesRepository userRolesRepository, UserEntity user) {
        this.usersRepository = usersRepository;
        this.userRolesRepository = userRolesRepository;
        this.user = user;
    }

    public static TestAccount create(UsersRepository usersRepository, RolesRepository rolesRepository,
                                     UserRolesRepository userRolesRepository, PasswordEncoder passwordEncoder,
                                     String... roleNames) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserEntity entity = new UserEntity();
        entity.setUsername("test-" + suffix);
        entity.setEmail("test-" + suffix + "@example.com");
        entity.setPassword(passwordEncoder.encode(PASSWORD));
        entity.setEnabled(true);

        TestAccount account = new TestAccount(usersRepository, userRolesRepository, usersRepository.save(entity));
        for (String roleName : roleNames) {
            UserRolesEntity link = new UserRolesEntity();
            link.setUser(account.user);
            link.setRole(findOrCreateRole(rolesRepository, roleName));
            account.roleLinks.add(userRolesRepository.save(link));
        }
        return account;
    }

    public String id() {
        return user.getId();
    }

    public String username() {
        return user.getUsername();
    }

    /** Logs in over HTTP and returns a ready-to-send {@code Authorization} value. */
    public String bearerToken(TestRestTemplate restTemplate) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username(), PASSWORD);
        return "Bearer " + restTemplate.postForEntity("/auth/login", new HttpEntity<>(body, headers), String.class)
                .getBody();
    }

    @Override
    public void close() {
        // delete(entity), not deleteById: UserRolesRepository declares UUID as its id type while
        // UserRolesEntity.id is a String, so deleteById does not compile against it.
        roleLinks.forEach(userRolesRepository::delete);
        usersRepository.deleteById(user.getId());
    }

    private static RoleEntity findOrCreateRole(RolesRepository rolesRepository, String name) {
        RoleEntity role = rolesRepository.findByNameEqualsIgnoreCase(name);
        if (role != null) {
            return role;
        }
        RoleEntity created = new RoleEntity();
        created.setName(name);
        return rolesRepository.save(created);
    }
}
