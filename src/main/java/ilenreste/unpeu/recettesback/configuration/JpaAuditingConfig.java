package ilenreste.unpeu.recettesback.configuration;

import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
import ilenreste.unpeu.recettesback.services.users.CurrentUserService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Wires Spring Data JPA auditing for {@link ilenreste.unpeu.recettesback.entities.AuditableEntity},
 * for {@code MediaEntity} and for {@code RecipeEntity}, which both opt in on
 * their own.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {

    /**
     * Resolves the user to stamp onto {@code created_by_id} / {@code updated_by_id},
     * and onto {@code recipes.author_id}.
     * <p>
     * "Who is calling" is answered by {@link CurrentUserService} rather than
     * re-implemented here — the guard it applies (a type check on the token, not
     * a null check) is easy to get subtly wrong, and a second copy would
     * eventually get it wrong differently.
     * <p>
     * {@code getReferenceById} returns a lazy proxy, so the foreign key is
     * genuine without costing a SELECT on every save. An empty result is the
     * right answer for anonymous requests, for seeding and for tests running
     * without a security context.
     * <p>
     * The repository arrives as an {@link ObjectProvider} so this bean does not
     * force the JPA repository infrastructure to bootstrap before the auditing
     * infrastructure that depends on it.
     */
    @Bean
    AuditorAware<UserEntity> auditorAware(ObjectProvider<UsersRepository> usersRepository,
                                          CurrentUserService currentUserService) {
        return () -> currentUserService.currentUserId()
                .map(userId -> usersRepository.getObject().getReferenceById(userId));
    }
}
