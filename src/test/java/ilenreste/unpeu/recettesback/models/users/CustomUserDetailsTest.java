package ilenreste.unpeu.recettesback.models.users;

import ilenreste.unpeu.recettesback.entities.users.RoleEntity;
import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CustomUserDetailsTest {

    @Test
    void exposesUnderlyingUserEntityFields() {
        RoleEntity userRole = new RoleEntity();
        userRole.setName("USER");

        UserEntity user = new UserEntity();
        user.setId("user-1");
        user.setUsername("jane");
        user.setPassword("hashed-password");
        user.setEnabled(true);
        user.setRoles(Set.of(userRole));

        CustomUserDetails userDetails = new CustomUserDetails(user);

        assertThat(userDetails.getId()).isEqualTo("user-1");
        assertThat(userDetails.getUsername()).isEqualTo("jane");
        assertThat(userDetails.getPassword()).isEqualTo("hashed-password");
        assertThat(userDetails.isEnabled()).isTrue();
        assertThat(userDetails.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("USER");
        assertThat(userDetails.isAccountNonExpired()).isTrue();
        assertThat(userDetails.isAccountNonLocked()).isTrue();
        assertThat(userDetails.isCredentialsNonExpired()).isTrue();
    }

    @Test
    void isEnabled_reflectsDisabledUserEntity() {
        UserEntity user = new UserEntity();
        user.setId("user-2");
        user.setUsername("disabled-jane");
        user.setPassword("hashed-password");
        user.setEnabled(false);
        user.setRoles(Set.of());

        CustomUserDetails userDetails = new CustomUserDetails(user);

        assertThat(userDetails.isEnabled()).isFalse();
        assertThat(userDetails.getAuthorities()).isEmpty();
    }
}
