package ilenreste.unpeu.recettesback.bootstrap;

import ilenreste.unpeu.recettesback.entities.reference.UnitEntity;
import ilenreste.unpeu.recettesback.entities.users.RoleEntity;
import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.entities.users.UserRolesEntity;
import ilenreste.unpeu.recettesback.repositories.reference.UnitsRepository;
import ilenreste.unpeu.recettesback.repositories.users.RolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UserRolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
import ilenreste.unpeu.recettesback.services.reference.ReferenceNameNormalizer;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Makes a fresh database usable without hand-written SQL.
 * <p>
 * Lives in {@code bootstrap} rather than {@code configuration}: it declares no
 * bean and configures nothing. It is an {@link ApplicationRunner} — behaviour
 * that runs once the context is up — and the only thing in the application whose
 * job is "put the rows in place that the rest of the code assumes already exist".
 * <p>
 * Without this the application has a four-level bootstrap trap:
 * {@code POST /recipes} needs a category, no category can be created without an
 * {@code ADMIN}, no account exists without a {@code USER} role row, and
 * {@code UserService.createUser} looks that role up rather than creating it. The
 * same trap already existed for {@code POST /users/create} and was documented as
 * "insert a row by hand".
 *
 * <h2>Why it is not a one-shot script</h2>
 * It is tempting to read this as a migration that has already run and could now
 * be deleted. It is not. There is <strong>no</strong> migration tool in this
 * project — {@code ddl-auto=update} builds the schema from the entities and
 * inserts no data — so this class is the only thing that populates an empty
 * database, and an empty database happens more often than "once, in production":
 * every fresh clone on a new machine, every CI run, every restore of a
 * schema-only dump, and every VPS rebuild. It is also what the
 * {@code @SpringBootTest} suites rely on for the {@code USER} role row rather
 * than each seeding it themselves.
 * <p>
 * Everything here is <strong>idempotent</strong>: it inserts only what is
 * missing, so it is safe on every startup rather than only the first. On a
 * database that is already populated it costs a handful of indexed lookups and
 * writes nothing. That is the whole reason it can be left switched on forever
 * instead of being a script somebody has to remember to run.
 * <p>
 * Categories and tags stay empty. Those are product choices — what this
 * particular recipe library is organised by — not defaults an application can
 * guess.
 */
@Log4j2
@Component
public class ReferenceDataSeeder implements ApplicationRunner {

    /**
     * Enough to write a French recipe on day one. Name as displayed, plus the
     * abbreviation the renderer uses.
     */
    private static final Map<String, String> STARTER_UNITS = new java.util.LinkedHashMap<>(Map.of(
            "gramme", "g",
            "kilogramme", "kg",
            "millilitre", "ml",
            "litre", "l",
            "cuillère à soupe", "c. à s.",
            "cuillère à café", "c. à c.",
            "pincée", "pincée"
    ));

    private static final List<String> ROLES = List.of("USER", "ADMIN");

    private final RolesRepository rolesRepository;
    private final UsersRepository usersRepository;
    private final UserRolesRepository userRolesRepository;
    private final UnitsRepository unitsRepository;
    private final ReferenceNameNormalizer normalizer;
    private final String bootstrapAdminUsername;

    public ReferenceDataSeeder(RolesRepository rolesRepository, UsersRepository usersRepository,
                               UserRolesRepository userRolesRepository, UnitsRepository unitsRepository,
                               ReferenceNameNormalizer normalizer,
                               @Value("${app.bootstrap.admin-username:}") String bootstrapAdminUsername) {
        this.rolesRepository = rolesRepository;
        this.usersRepository = usersRepository;
        this.userRolesRepository = userRolesRepository;
        this.unitsRepository = unitsRepository;
        this.normalizer = normalizer;
        this.bootstrapAdminUsername = bootstrapAdminUsername;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedRoles();
        seedUnits();
        grantBootstrapAdmin();
    }

    private void seedRoles() {
        for (String roleName : ROLES) {
            if (rolesRepository.findByNameEqualsIgnoreCase(roleName) == null) {
                RoleEntity role = new RoleEntity();
                role.setName(roleName);
                rolesRepository.save(role);
                log.info("Seeded missing role {}", roleName);
            }
        }
    }

    private void seedUnits() {
        STARTER_UNITS.forEach((name, abbreviation) -> {
            String normalized = normalizer.normalize(name);
            if (!unitsRepository.existsByNormalizedName(normalized)) {
                UnitEntity unit = new UnitEntity();
                unit.setName(name);
                unit.setNormalizedName(normalized);
                unit.setAbbreviation(abbreviation);
                unitsRepository.save(unit);
                log.info("Seeded missing unit {}", name);
            }
        });
    }

    /**
     * Promotes an <strong>existing</strong> account to {@code ADMIN}.
     * <p>
     * Deliberately narrow. It never creates an account and never reads a password
     * from configuration: a seeder that can mint a privileged login turns a
     * leaked properties file into a full account takeover, and a default admin
     * password is the single most reliably exploited thing in a deployed
     * application. Register normally, then name yourself here.
     * <p>
     * Note admins need {@code USER} as well, or the {@code hasRole("USER")} URL
     * rules lock them out — which is why the grant is additive rather than a
     * replacement.
     */
    private void grantBootstrapAdmin() {
        if (bootstrapAdminUsername == null || bootstrapAdminUsername.isBlank()) {
            return;
        }
        usersRepository.findByUsername(bootstrapAdminUsername).ifPresentOrElse(
                this::grantAdminTo,
                () -> log.warn("app.bootstrap.admin-username is set to '{}', but no such account exists. "
                        + "Register it first; nothing was granted.", bootstrapAdminUsername));
    }

    private void grantAdminTo(UserEntity user) {
        boolean alreadyAdmin = user.getRoles().stream()
                .anyMatch(role -> "ADMIN".equalsIgnoreCase(role.getName()));
        if (alreadyAdmin) {
            return;
        }
        // Written through UserRolesEntity, not user.getRoles().add(...): user_roles is mapped both
        // as the @ManyToMany join table and as an entity with its own id column, so a @ManyToMany
        // insert leaves that NOT NULL id null and fails. UserService.createUser does the same.
        UserRolesEntity link = new UserRolesEntity();
        link.setUser(user);
        link.setRole(rolesRepository.findByNameEqualsIgnoreCase("ADMIN"));
        userRolesRepository.save(link);
        log.warn("Granted ADMIN to '{}' via app.bootstrap.admin-username", user.getUsername());
    }
}
