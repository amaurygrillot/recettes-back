package ilenreste.unpeu.recettesback.services.users;

import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /users/create} against the real database, committed for real.
 * <p>
 * {@code UserServiceTest} mocks every repository, so it cannot see a failure that only happens when
 * Hibernate flushes at commit. That is exactly where this one lived: {@code createUser} added the role
 * to the managed {@code UserEntity.roles} as well as writing the {@code UserRolesEntity} link, and the
 * resulting {@code @ManyToMany} insert into {@code user_roles} left its NOT NULL {@code id} null. The
 * account was logged as created and then rolled back.
 * <p>
 * No transaction wraps a real HTTP call, so the account is deleted by hand afterwards.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class UserCreationIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private UsersRepository usersRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String username = "create-" + UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void deleteTheAccount() {
        usersRepository.findByUsername(username).ifPresent(user -> {
            jdbcTemplate.update("delete from user_roles where user_id = ?", user.getId());
            usersRepository.delete(user);
        });
    }

    @Test
    void createUser_commitsTheAccountAndItsUserRole() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"username":"%s","password":"Password123","email":"%s@example.com","firstname":"Jane","lastname":"Doe"}
                """.formatted(username, username);

        ResponseEntity<String> response =
                restTemplate.postForEntity("/users/create", new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("\"roles\":[\"USER\"]");
        assertThat(usersRepository.existsByUsername(username)).isTrue();
        // Exactly one link: a second, id-less insert from the @ManyToMany side is what used to fail.
        Integer links = jdbcTemplate.queryForObject(
                "select count(*) from user_roles ur join users u on u.id = ur.user_id where u.username = ?",
                Integer.class, username);
        assertThat(links).isEqualTo(1);
    }
}
