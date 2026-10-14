package ilenreste.unpeu.recettesback.entities;

import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Who created and last touched a row, and when — stamped by Spring Data JPA
 * auditing rather than by hand in every service, which is precisely the
 * duplication that gets forgotten in one branch.
 * <p>
 * Lives at the {@code entities} root rather than in a domain sub-package: it is
 * the shared supertype, owned by no domain.
 * <p>
 * {@link EntityListeners} is not optional. Without the
 * {@link AuditingEntityListener} the annotations below are inert and every
 * column silently stays null — which, on a {@code NOT NULL} column, surfaces
 * much later as a constraint violation on insert.
 * <p>
 * {@code createdBy} is not optional either: {@code ingredients} may be created
 * by any authenticated user, so this is the only record of who added a shared
 * row, and it is what an admin reads before merging a duplicate. Under
 * {@code ddl-auto=update} an omitted field means the column is never created —
 * silently, with no error — and the information cannot be recovered afterwards.
 * <p>
 * Note that Spring Data stamps <em>both</em> pairs on insert, so {@code updatedAt}
 * equals {@code createdAt} and {@code updatedBy} equals {@code createdBy} on a
 * freshly created row rather than being null. That is what keeps
 * {@code updatedAt} safely {@code NOT NULL}.
 *
 * @see ilenreste.unpeu.recettesback.configuration.JpaAuditingConfig
 */
@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableEntity {

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", updatable = false)
    private UserEntity createdBy;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    @LastModifiedBy
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by_id")
    private UserEntity updatedBy;
}
