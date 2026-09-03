package ilenreste.unpeu.recettesback.configuration;

import ilenreste.unpeu.recettesback.entities.reference.UnitEntity;
import ilenreste.unpeu.recettesback.entities.users.RoleEntity;
import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.entities.users.UserRolesEntity;
import ilenreste.unpeu.recettesback.repositories.reference.UnitsRepository;
import ilenreste.unpeu.recettesback.repositories.users.RolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UserRolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
import ilenreste.unpeu.recettesback.services.reference.ReferenceNameNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReferenceDataSeederTest {

    private RolesRepository rolesRepository;
    private UsersRepository usersRepository;
    private UserRolesRepository userRolesRepository;
    private UnitsRepository unitsRepository;

    @BeforeEach
    void setUp() {
        rolesRepository = mock(RolesRepository.class);
        usersRepository = mock(UsersRepository.class);
        userRolesRepository = mock(UserRolesRepository.class);
        unitsRepository = mock(UnitsRepository.class);
    }

    private ReferenceDataSeeder seeder(String bootstrapAdminUsername) {
        return new ReferenceDataSeeder(rolesRepository, usersRepository, userRolesRepository,
                unitsRepository, new ReferenceNameNormalizer(), bootstrapAdminUsername);
    }

    @Test
    void createsTheRolesAndUnitsThatAreMissing() {
        // A fresh database: POST /recipes needs a category, no category exists without an ADMIN, no
        // account exists without a USER role row, and UserService.createUser looks that role up
        // rather than creating it. Four levels of bootstrap trap, all closed here.
        when(rolesRepository.findByNameEqualsIgnoreCase(anyString())).thenReturn(null);
        when(unitsRepository.existsByNormalizedName(anyString())).thenReturn(false);

        seeder("").run(null);

        ArgumentCaptor<RoleEntity> roles = ArgumentCaptor.forClass(RoleEntity.class);
        verify(rolesRepository, times(2)).save(roles.capture());
        assertThat(roles.getAllValues()).extracting(RoleEntity::getName)
                .containsExactly("USER", "ADMIN");

        ArgumentCaptor<UnitEntity> units = ArgumentCaptor.forClass(UnitEntity.class);
        verify(unitsRepository, times(7)).save(units.capture());
        assertThat(units.getAllValues()).extracting(UnitEntity::getName)
                .contains("gramme", "cuillère à soupe", "pincée");
        // Normalized through the same component the services use, so a later "create gramme"
        // request collides with this row instead of adding a duplicate.
        assertThat(units.getAllValues()).extracting(UnitEntity::getNormalizedName)
                .contains("cuillere a soupe", "pincee");
    }

    @Test
    void insertsNothingWhenEverythingAlreadyExists() {
        // Runs on every startup, not just the first, so it has to be idempotent.
        when(rolesRepository.findByNameEqualsIgnoreCase(anyString())).thenReturn(new RoleEntity());
        when(unitsRepository.existsByNormalizedName(anyString())).thenReturn(true);

        seeder("").run(null);

        verify(rolesRepository, never()).save(any());
        verify(unitsRepository, never()).save(any());
    }

    @Test
    void grantsNoAdmin_whenTheBootstrapUsernameIsBlank() {
        when(rolesRepository.findByNameEqualsIgnoreCase(anyString())).thenReturn(new RoleEntity());
        when(unitsRepository.existsByNormalizedName(anyString())).thenReturn(true);

        seeder("").run(null);
        seeder(null).run(null);

        verifyNoInteractions(usersRepository, userRolesRepository);
    }

    @Test
    void grantsAdminToAnExistingAccount() {
        when(rolesRepository.findByNameEqualsIgnoreCase("USER")).thenReturn(new RoleEntity());
        RoleEntity adminRole = new RoleEntity();
        adminRole.setName("ADMIN");
        when(rolesRepository.findByNameEqualsIgnoreCase("ADMIN")).thenReturn(adminRole);
        when(unitsRepository.existsByNormalizedName(anyString())).thenReturn(true);

        UserEntity jane = new UserEntity();
        jane.setUsername("jane");
        when(usersRepository.findByUsername("jane")).thenReturn(Optional.of(jane));

        seeder("jane").run(null);

        ArgumentCaptor<UserRolesEntity> link = ArgumentCaptor.forClass(UserRolesEntity.class);
        verify(userRolesRepository).save(link.capture());
        assertThat(link.getValue().getUser()).isEqualTo(jane);
        assertThat(link.getValue().getRole()).isEqualTo(adminRole);
    }

    @Test
    void createsNoAccount_whenTheBootstrapUsernameDoesNotExist() {
        // The narrowness is the point: a seeder that can mint a privileged login turns a leaked
        // properties file into an account takeover, and a default admin password is the single most
        // reliably exploited thing in a deployed application.
        when(rolesRepository.findByNameEqualsIgnoreCase(anyString())).thenReturn(new RoleEntity());
        when(unitsRepository.existsByNormalizedName(anyString())).thenReturn(true);
        when(usersRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        seeder("ghost").run(null);

        verify(usersRepository, never()).save(any());
        verifyNoInteractions(userRolesRepository);
    }

    @Test
    void doesNotGrantAdminTwice() {
        when(rolesRepository.findByNameEqualsIgnoreCase(anyString())).thenReturn(new RoleEntity());
        when(unitsRepository.existsByNormalizedName(anyString())).thenReturn(true);

        RoleEntity admin = new RoleEntity();
        admin.setName("ADMIN");
        UserEntity jane = new UserEntity();
        jane.setUsername("jane");
        jane.setRoles(Set.of(admin));
        when(usersRepository.findByUsername("jane")).thenReturn(Optional.of(jane));

        seeder("jane").run(null);

        verifyNoInteractions(userRolesRepository);
    }
}
