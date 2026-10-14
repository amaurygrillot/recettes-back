package ilenreste.unpeu.recettesback.services.users;

import ilenreste.unpeu.recettesback.entities.users.RoleEntity;
import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.entities.users.UserRolesEntity;
import ilenreste.unpeu.recettesback.exceptions.ForbiddenOperationException;
import ilenreste.unpeu.recettesback.exceptions.ResourceConflictException;
import ilenreste.unpeu.recettesback.mappers.users.UserMapper;
import ilenreste.unpeu.recettesback.exceptions.ResourceNotFoundException;
import ilenreste.unpeu.recettesback.models.users.requests.CreateUserRequest;
import ilenreste.unpeu.recettesback.models.users.requests.UpdateUserRequest;
import ilenreste.unpeu.recettesback.models.users.responses.UserResponse;
import ilenreste.unpeu.recettesback.repositories.users.RolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UserRolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UsersRepository usersRepository;
    @Mock
    private RolesRepository rolesRepository;
    @Mock
    private UserRolesRepository userRolesRepository;
    @Mock
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(usersRepository, rolesRepository, userRolesRepository,
                passwordEncoder, new UserMapper());
    }

    @Test
    void createUser_throwsConflict_whenUsernameAlreadyExists() {
        CreateUserRequest request = new CreateUserRequest("jane", "Password123", "jane@example.com", "Jane", "Doe");
        when(usersRepository.existsByUsername("jane")).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser(request))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessage("Username already exists");

        verifyNoInteractions(rolesRepository, userRolesRepository, passwordEncoder);
    }

    @Test
    void createUser_savesHashedPasswordAndAssignsUserRole_whenUsernameIsAvailable() {
        CreateUserRequest request = new CreateUserRequest("jane", "Password123", "jane@example.com", "Jane", "Doe");
        when(usersRepository.existsByUsername("jane")).thenReturn(false);
        when(passwordEncoder.encode("Password123")).thenReturn("hashed-password");

        UserEntity savedUser = new UserEntity();
        savedUser.setId("user-1");
        when(usersRepository.save(any(UserEntity.class))).thenReturn(savedUser);

        RoleEntity userRole = new RoleEntity();
        userRole.setName("USER");
        when(rolesRepository.findByNameEqualsIgnoreCase("USER")).thenReturn(userRole);

        UserResponse created = userService.createUser(request);

        // The response is the confirmation the endpoint had no way to give before: an id to
        // follow, and the role the account actually ended up with.
        assertThat(created.id()).isEqualTo("user-1");
        assertThat(created.roles()).containsExactly("USER");

        ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(usersRepository).save(userCaptor.capture());
        UserEntity persistedUser = userCaptor.getValue();
        assertThat(persistedUser.getUsername()).isEqualTo("jane");
        assertThat(persistedUser.getPassword()).isEqualTo("hashed-password");
        assertThat(persistedUser.isEnabled()).isTrue();
        assertThat(persistedUser.getEmail()).isEqualTo("jane@example.com");
        assertThat(persistedUser.getFirstname()).isEqualTo("Jane");
        assertThat(persistedUser.getLastname()).isEqualTo("Doe");

        ArgumentCaptor<UserRolesEntity> userRolesCaptor = ArgumentCaptor.forClass(UserRolesEntity.class);
        verify(userRolesRepository).save(userRolesCaptor.capture());
        assertThat(userRolesCaptor.getValue().getUser()).isEqualTo(savedUser);
        assertThat(userRolesCaptor.getValue().getRole()).isEqualTo(userRole);
    }

    @Test
    void createUser_failsLoudly_whenTheUserRoleHasNotBeenSeeded() {
        // Left unchecked this surfaced two statements later as a NOT NULL violation on
        // user_roles.role_id: a 500 whose message pointed at the join table rather than at the
        // missing row, and which the password-reset advice would then have reported as a 400.
        CreateUserRequest request = new CreateUserRequest("jane", "Password123", "jane@example.com", "Jane", "Doe");
        when(usersRepository.existsByUsername("jane")).thenReturn(false);
        when(rolesRepository.findByNameEqualsIgnoreCase("USER")).thenReturn(null);

        assertThatThrownBy(() -> userService.createUser(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("USER");

        // Nothing is written: no half-created account without its role.
        verify(usersRepository, never()).save(any());
        verify(userRolesRepository, never()).save(any());
    }

    /** Every field absent: the shape a partial update that touches nothing takes. */
    private static UpdateUserRequest nothing() {
        return new UpdateUserRequest(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * A stored account whose password hash is {@code "current-hash"}.
     * <p>
     * The {@code matches} stub is deliberately <em>not</em> here: the refusal tests
     * never reach it, and stubbing it for them would be the unnecessary stubbing
     * Mockito's strict mode rejects. Tests that expect the check to pass call
     * {@link #currentPasswordIsCorrect()}.
     */
    private UserEntity accountWithKnownPassword() {
        UserEntity user = new UserEntity();
        user.setId("user-1");
        user.setUsername("old-username");
        user.setEmail("old@example.com");
        user.setFirstname("Old");
        user.setLastname("Name");
        user.setPassword("current-hash");
        when(usersRepository.findById("user-1")).thenReturn(Optional.of(user));
        return user;
    }

    /** Makes {@code "CurrentPassword1"} the account's correct current password. */
    private void currentPasswordIsCorrect() {
        when(passwordEncoder.matches("CurrentPassword1", "current-hash")).thenReturn(true);
    }

    @Test
    void updateUserById_throwsNotFound_whenUserDoesNotExist() {
        when(usersRepository.findById("missing-id")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateUser("missing-id", nothing()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("No user with id missing-id");

        verify(usersRepository, never()).save(any());
    }

    @Test
    void updateUser_appliesANonCredentialFieldWithoutAskingForTheCurrentPassword() {
        // Renaming yourself is not a credential change: demanding a password to fix a typo would
        // train people to type it into whatever asks.
        UserEntity user = new UserEntity();
        user.setId("user-1");
        when(usersRepository.findById("user-1")).thenReturn(Optional.of(user));
        returnsWhatItIsGiven();
        UpdateUserRequest request = new UpdateUserRequest(
                Optional.of("newname"), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());

        userService.updateUser("user-1", request);

        assertThat(user.getUsername()).isEqualTo("newname");
        verify(usersRepository).save(user);
    }

    @Test
    void updateUser_appliesEveryPresentField_andHashesNewPassword() {
        UserEntity user = accountWithKnownPassword();
        currentPasswordIsCorrect();
        when(passwordEncoder.encode("NewPassword123")).thenReturn("new-hash");
        returnsWhatItIsGiven();

        UpdateUserRequest request = new UpdateUserRequest(
                Optional.of("new-username"),
                Optional.of("NewPassword123"),
                Optional.of("new@example.com"),
                Optional.of("New"),
                Optional.of("Lastname"),
                Optional.of("CurrentPassword1"));

        userService.updateUser("user-1", request);

        assertThat(user.getUsername()).isEqualTo("new-username");
        assertThat(user.getEmail()).isEqualTo("new@example.com");
        assertThat(user.getFirstname()).isEqualTo("New");
        assertThat(user.getLastname()).isEqualTo("Lastname");
        assertThat(user.getPassword()).isEqualTo("new-hash");
        verify(usersRepository).save(user);
    }

    @Test
    void updateUser_leavesEveryFieldUnchanged_whenRequestHasNoPresentFields() {
        UserEntity user = new UserEntity();
        user.setId("user-1");
        user.setUsername("unchanged");
        user.setEmail("unchanged@example.com");
        user.setFirstname("Unchanged");
        user.setLastname("Person");
        user.setPassword("unchanged-hash");
        when(usersRepository.findById("user-1")).thenReturn(Optional.of(user));
        returnsWhatItIsGiven();

        userService.updateUser("user-1", nothing());

        assertThat(user.getUsername()).isEqualTo("unchanged");
        assertThat(user.getEmail()).isEqualTo("unchanged@example.com");
        assertThat(user.getFirstname()).isEqualTo("Unchanged");
        assertThat(user.getLastname()).isEqualTo("Person");
        assertThat(user.getPassword()).isEqualTo("unchanged-hash");
        verifyNoInteractions(passwordEncoder);
        verify(usersRepository).save(user);
    }

    /*
     * The guard below is the whole point of currentPassword: a bearer token proves the caller held
     * a valid login in the last hour, not that they are the account holder. A password change
     * outlives the token that authorised it and an email change takes the account's only route
     * back, so together one leaked token became an account the owner could not recover.
     */

    @Test
    void updateUser_refusesAPasswordChangeWithNoCurrentPassword() {
        UserEntity user = accountWithKnownPassword();

        UpdateUserRequest request = new UpdateUserRequest(
                Optional.empty(), Optional.of("NewPassword123"), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());

        assertThatThrownBy(() -> userService.updateUser("user-1", request))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("current password");

        assertThat(user.getPassword()).isEqualTo("current-hash");
        verify(usersRepository, never()).save(any());
    }

    @Test
    void updateUser_refusesAPasswordChangeWithTheWrongCurrentPassword() {
        UserEntity user = accountWithKnownPassword();
        when(passwordEncoder.matches("Guess", "current-hash")).thenReturn(false);

        UpdateUserRequest request = new UpdateUserRequest(
                Optional.empty(), Optional.of("NewPassword123"), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of("Guess"));

        assertThatThrownBy(() -> userService.updateUser("user-1", request))
                .isInstanceOf(ForbiddenOperationException.class);

        assertThat(user.getPassword()).isEqualTo("current-hash");
        verify(usersRepository, never()).save(any());
    }

    @Test
    void updateUser_refusesAnEmailChangeWithNoCurrentPassword() {
        // Gated for the same reason: POST /auth/reinit-password mails the stored address, so
        // replacing it unverified removes the way back into the account.
        UserEntity user = accountWithKnownPassword();

        UpdateUserRequest request = new UpdateUserRequest(
                Optional.empty(), Optional.empty(), Optional.of("attacker@example.com"),
                Optional.empty(), Optional.empty(), Optional.empty());

        assertThatThrownBy(() -> userService.updateUser("user-1", request))
                .isInstanceOf(ForbiddenOperationException.class);

        assertThat(user.getEmail()).isEqualTo("old@example.com");
        verify(usersRepository, never()).save(any());
    }

    @Test
    void applyNewPassword_asksForNoCurrentPassword_becauseAForgottenOneCannotBeSupplied() {
        // The forgotten-password flow. PasswordResetService has already consumed the emailed
        // one-time token, which is the proof of identity here; routing it through updateUser would
        // lock out exactly the people who need to get back in.
        UserEntity user = new UserEntity();
        user.setId("user-1");
        user.setPassword("forgotten-hash");
        when(passwordEncoder.encode("BrandNewPassword1")).thenReturn("reset-hash");
        returnsWhatItIsGiven();

        userService.applyNewPassword(user, "BrandNewPassword1");

        assertThat(user.getPassword()).isEqualTo("reset-hash");
        verify(usersRepository).save(user);
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void updateUser_refusesAUsernameAlreadyTakenByAnotherAccount() {
        UserEntity user = new UserEntity();
        user.setId("user-1");
        user.setUsername("mine");
        when(usersRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(usersRepository.existsByUsername("taken")).thenReturn(true);

        UpdateUserRequest request = new UpdateUserRequest(
                Optional.of("taken"), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());

        // A 409 naming the field, not a DataIntegrityViolationException at commit time - which
        // would have surfaced after "Updated user {id}" was already logged as a success.
        assertThatThrownBy(() -> userService.updateUser("user-1", request))
                .isInstanceOf(ResourceConflictException.class);

        verify(usersRepository, never()).save(any());
    }

    @Test
    void updateUser_allowsAnAccountToKeepItsOwnUsername() {
        // Re-sending your own name is not a conflict with yourself.
        UserEntity user = new UserEntity();
        user.setId("user-1");
        user.setUsername("mine");
        when(usersRepository.findById("user-1")).thenReturn(Optional.of(user));
        returnsWhatItIsGiven();

        UpdateUserRequest request = new UpdateUserRequest(
                Optional.of("mine"), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());

        userService.updateUser("user-1", request);

        verify(usersRepository, never()).existsByUsername(any());
        verify(usersRepository).save(user);
    }

    /**
     * The service maps the row {@code save} hands back, not the detached input, so that the
     * response carries what the persistence context actually flushed.
     */
    private void returnsWhatItIsGiven() {
        when(usersRepository.save(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }
}
