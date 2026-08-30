package ilenreste.unpeu.recettesback.services.users;

import ilenreste.unpeu.recettesback.entities.users.RoleEntity;
import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.entities.users.UserRolesEntity;
import ilenreste.unpeu.recettesback.models.users.requests.CreateUserRequest;
import ilenreste.unpeu.recettesback.models.users.requests.UpdateUserRequest;
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
        userService = new UserService(usersRepository, rolesRepository, userRolesRepository, passwordEncoder);
    }

    @Test
    void createUser_throwsIllegalState_whenUsernameAlreadyExists() {
        CreateUserRequest request = new CreateUserRequest("jane", "Password123", "jane@example.com", "Jane", "Doe");
        when(usersRepository.existsByUsername("jane")).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser(request))
                .isInstanceOf(IllegalStateException.class)
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

        userService.createUser(request);

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
    void updateUserById_throwsIllegalState_whenUserDoesNotExist() {
        when(usersRepository.findById("missing-id")).thenReturn(Optional.empty());
        UpdateUserRequest request = new UpdateUserRequest(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        assertThatThrownBy(() -> userService.updateUser("missing-id", request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("User doesn't exist");

        verify(usersRepository, never()).save(any());
    }

    @Test
    void updateUserById_looksUpUserThenDelegatesToEntityOverload() {
        UserEntity user = new UserEntity();
        user.setId("user-1");
        when(usersRepository.findById("user-1")).thenReturn(Optional.of(user));
        UpdateUserRequest request = new UpdateUserRequest(
                Optional.of("newname"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        userService.updateUser("user-1", request);

        assertThat(user.getUsername()).isEqualTo("newname");
        verify(usersRepository).save(user);
    }

    @Test
    void updateUser_appliesEveryPresentField_andHashesNewPassword() {
        UserEntity user = new UserEntity();
        user.setUsername("old-username");
        user.setEmail("old@example.com");
        user.setFirstname("Old");
        user.setLastname("Name");
        user.setPassword("old-hash");
        when(passwordEncoder.encode("NewPassword123")).thenReturn("new-hash");

        UpdateUserRequest request = new UpdateUserRequest(
                Optional.of("new-username"),
                Optional.of("NewPassword123"),
                Optional.of("new@example.com"),
                Optional.of("New"),
                Optional.of("Lastname"));

        userService.updateUser(user, request);

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
        user.setUsername("unchanged");
        user.setEmail("unchanged@example.com");
        user.setFirstname("Unchanged");
        user.setLastname("Person");
        user.setPassword("unchanged-hash");

        UpdateUserRequest request = new UpdateUserRequest(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        userService.updateUser(user, request);

        assertThat(user.getUsername()).isEqualTo("unchanged");
        assertThat(user.getEmail()).isEqualTo("unchanged@example.com");
        assertThat(user.getFirstname()).isEqualTo("Unchanged");
        assertThat(user.getLastname()).isEqualTo("Person");
        assertThat(user.getPassword()).isEqualTo("unchanged-hash");
        verifyNoInteractions(passwordEncoder);
        verify(usersRepository).save(user);
    }
}
