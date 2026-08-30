package ilenreste.unpeu.recettesback.services;

import ilenreste.unpeu.recettesback.entities.UserEntity;
import ilenreste.unpeu.recettesback.models.users.CustomUserDetails;
import ilenreste.unpeu.recettesback.repositories.UsersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatabaseUserDetailsServiceTest {

    @Mock
    private UsersRepository usersRepository;

    private DatabaseUserDetailsService databaseUserDetailsService;

    @BeforeEach
    void setUp() {
        databaseUserDetailsService = new DatabaseUserDetailsService(usersRepository);
    }

    @Test
    void loadUserByUsername_returnsCustomUserDetails_whenUserExists() {
        UserEntity user = new UserEntity();
        user.setId("user-1");
        user.setUsername("jane");
        user.setPassword("hashed-password");
        user.setEnabled(true);
        user.setRoles(Set.of());
        when(usersRepository.findByUsername("jane")).thenReturn(Optional.of(user));

        UserDetails userDetails = databaseUserDetailsService.loadUserByUsername("jane");

        assertThat(userDetails).isInstanceOf(CustomUserDetails.class);
        assertThat(userDetails.getUsername()).isEqualTo("jane");
        assertThat(((CustomUserDetails) userDetails).getId()).isEqualTo("user-1");
    }

    @Test
    void loadUserByUsername_throwsUsernameNotFoundException_whenUserDoesNotExist() {
        when(usersRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> databaseUserDetailsService.loadUserByUsername("ghost"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("User not found");
    }
}
