package ilenreste.unpeu.recettesback.configuration;

import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;

/**
 * Wires Spring Data JPA auditing for {@link ilenreste.unpeu.recettesback.entities.AuditableEntity}
 * and for {@code MediaEntity}, which opts in on its own.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {

    /**
     * Resolves the user to stamp onto {@code created_by_id} / {@code updated_by_id}.
     * <p>
     * <strong>The guard is a type check, not a null check.</strong> Anonymous
     * requests do not stop at {@code JwtAuthenticationFilter}; they continue
     * down the chain, and Spring Security's {@code AnonymousAuthenticationFilter}
     * puts an {@code AnonymousAuthenticationToken} in the context whose principal
     * is the <em>String</em> {@code "anonymousUser"}. So "no authentication" is
     * not {@code authentication == null}, and the obvious version — null check
     * then cast — takes the non-empty branch on an anonymous request and throws
     * {@link ClassCastException}. {@code POST /users/create} is {@code permitAll()}
     * and writes, so that path is reachable.
     * <p>
     * Filtering on {@code isAuthenticated()} would not catch it either:
     * {@code AnonymousAuthenticationToken} reports {@code true}.
     * <p>
     * {@code getReferenceById} returns a lazy proxy, so the foreign key is
     * genuine without costing a SELECT on every save. Empty is also the right
     * answer for seeding and for tests running without a security context.
     * <p>
     * The repository arrives as an {@link ObjectProvider} so this bean does not
     * force the JPA repository infrastructure to bootstrap before the auditing
     * infrastructure that depends on it.
     */
    @Bean
    AuditorAware<UserEntity> auditorAware(ObjectProvider<UsersRepository> usersRepository) {
        return () -> Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(JwtAuthenticationToken.class::cast)
                .map(authentication -> (String) authentication.getToken().getClaims().get("userId"))
                .map(userId -> usersRepository.getObject().getReferenceById(userId));
    }
}
