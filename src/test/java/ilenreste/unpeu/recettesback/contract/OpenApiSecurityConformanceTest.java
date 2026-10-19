package ilenreste.unpeu.recettesback.contract;

import ilenreste.unpeu.recettesback.repositories.users.RolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UserRolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
import ilenreste.unpeu.recettesback.support.TestAccount;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may call what is written down twice: per operation in {@code openapi.yaml} ({@code security}
 * and {@code x-required-role}), and per URL pattern in {@code SecurityFilterConfig}. Nothing makes
 * the two agree, and a contract that says "public" about a route the filter chain locks is worse than
 * no contract. This test sends every operation in the YAML to the real filter chain as four kinds of
 * caller and checks the answer against what the YAML promises.
 * <p>
 * It never needs a request to succeed, only to get past security, so every request is built to be
 * harmless: ids that name nothing, malformed JSON as a JSON body, and a multipart body with no parts.
 * Spring refuses each of those with a 400 after security has had its say and before any controller or
 * service runs. Nothing is created, changed or deleted; the only rows are the three throwaway accounts.
 * <ul>
 *   <li>a <strong>public</strong> operation answers every caller with something other than 401/403;</li>
 *   <li>a <strong>protected</strong> one answers an anonymous caller 401, a caller whose token lacks
 *       the required role 403, and a caller who holds it - or, with no role named, any logged-in caller -
 *       anything but 401/403, and never a 5xx.</li>
 * </ul>
 * Every answer must also be a status the operation's {@code responses} document. That is the one place the
 * YAML's statuses meet the running code: for the generated operations everything else in the served spec is
 * rendered from the YAML, so only a real request can show that the contract left one out.
 * <p>
 * "Only the author or an admin may edit a recipe" is the service's rule, not the URL rules', and is covered
 * by {@code RecipesIntegrationTest}; the YAML says "any authenticated caller" for those operations because
 * that is as far as security goes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenApiSecurityConformanceTest {

    private static final String BOUNDARY = "conformance-test-boundary";
    private static final String ANONYMOUS = "anonymous";
    private static final String NO_ROLE = "logged in, no role";
    private static final String USER = "USER";
    private static final String ADMIN = "USER+ADMIN";

    /** Who each kind of caller is: the roles its token carries (empty for anonymous). */
    private static final Map<String, Set<String>> ROLES = Map.of(
            ANONYMOUS, Set.of(),
            NO_ROLE, Set.of(),
            USER, Set.of("USER"),
            ADMIN, Set.of("USER", "ADMIN"));

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private UsersRepository usersRepository;
    @Autowired
    private RolesRepository rolesRepository;
    @Autowired
    private UserRolesRepository userRolesRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final Map<String, String> authorizations = new LinkedHashMap<>();
    private final List<TestAccount> accounts = new ArrayList<>();

    @BeforeAll
    void createCallers() {
        // An admin needs USER as well, or the hasRole("USER") URL rules lock them out.
        login(NO_ROLE);
        login(USER, "USER");
        login(ADMIN, "USER", "ADMIN");
    }

    @AfterAll
    void deleteCallers() {
        accounts.forEach(TestAccount::close);
    }

    private void login(String caller, String... roles) {
        TestAccount account = TestAccount.create(usersRepository, rolesRepository, userRolesRepository,
                passwordEncoder, roles);
        accounts.add(account);
        authorizations.put(caller, account.bearerToken(restTemplate));
    }

    static Stream<Arguments> everyOperationForEveryCaller() {
        return OpenApiContract.operations().stream()
                .flatMap(operation -> ROLES.keySet().stream().map(caller -> Arguments.of(operation, caller)));
    }

    @Test
    void theContractDescribesEnoughOperationsForThisTestToMeanSomething() {
        assertThat(OpenApiContract.operations()).hasSizeGreaterThan(30);
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("everyOperationForEveryCaller")
    void securityGivesEachCallerTheAnswerTheContractPromises(OpenApiContract.Operation operation, String caller) {
        int status = send(operation, authorizations.get(caller)).getStatusCode().value();

        assertThat(operation.statuses())
                .as("%s answered %d to a %s caller, and its responses do not document that", operation, status, caller)
                .contains(String.valueOf(status));

        if (operation.isPublic()) {
            assertThat(status).as("%s is public, so security must let a %s caller through", operation, caller)
                    .isNotIn(401, 403).isLessThan(500);
        } else if (ANONYMOUS.equals(caller)) {
            assertThat(status).as("%s needs a token, so an anonymous caller is unauthorized", operation)
                    .isEqualTo(401);
        } else if (operation.requiredRole() == null || ROLES.get(caller).contains(operation.requiredRole())) {
            assertThat(status).as("%s needs %s, which a %s caller has", operation,
                    operation.requiredRole() == null ? "any login" : "the " + operation.requiredRole() + " role",
                    caller).isNotIn(401, 403).isLessThan(500);
        } else {
            assertThat(status).as("%s needs the %s role, which a %s caller lacks", operation,
                    operation.requiredRole(), caller).isEqualTo(403);
        }
    }

    private ResponseEntity<String> send(OpenApiContract.Operation operation, String authorization) {
        HttpHeaders headers = new HttpHeaders();
        if (authorization != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        if (operation.bodyType() != null) {
            headers.set(HttpHeaders.CONTENT_TYPE, operation.bodyType());
        }
        // Harmless on purpose, see the class comment: broken JSON, or a multipart body that has a boundary and no parts.
        boolean multipart = operation.bodyType() != null && operation.bodyType().startsWith("multipart/");
        if (multipart) {
            headers.set(HttpHeaders.CONTENT_TYPE, operation.bodyType() + "; boundary=" + BOUNDARY);
        }
        String body = operation.bodyType() == null ? null : multipart ? "--" + BOUNDARY + "--\r\n" : "{";
        String path = operation.path().replace("{id}", "no-such-" + UUID.randomUUID());
        return restTemplate.exchange(path, HttpMethod.valueOf(operation.method()), new HttpEntity<>(body, headers),
                String.class);
    }
}
