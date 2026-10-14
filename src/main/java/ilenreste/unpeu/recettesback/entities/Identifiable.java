package ilenreste.unpeu.recettesback.entities;

/**
 * An entity that can be asked for its own id.
 * <p>
 * Lives at the {@code entities} root for the same reason {@link AuditableEntity}
 * does: it is a shared supertype, owned by no domain.
 * <p>
 * It exists for one caller — {@code ReferenceResolver}, which loads a batch of
 * ids and keys the result by id. Without a bound, its type parameter was a bare
 * {@code T} and every call site had to hand it a second argument saying how to
 * read an id back off an entity ({@code CategoryEntity::getId},
 * {@code UnitEntity::getId}, five of them, all the same method reference). The
 * bound replaces that parameter: the resolver can now ask the entity directly,
 * and a type that has no id cannot be passed at all.
 * <p>
 * Every implementor gets the method from Lombok's {@code @Getter} on its
 * {@code id} field, so nothing hand-written satisfies this interface — but the
 * compiler still checks that the generated method is there, which is the point.
 * <p>
 * The narrower bound the review asked for, {@code ReferenceEntity}, cannot be
 * used: {@code MediaEntity} is resolved by the same five-line batch loader and is
 * deliberately not reference data — it has no name, no normalised name, and does
 * not extend {@link AuditableEntity} because media rows are immutable. Bounding
 * on the reference supertype would mean either duplicating the resolver for media
 * or dropping the batch load for it, which is the one thing it exists to do. This
 * is the narrowest bound that covers all five call sites, and it is exactly what
 * the resolver uses.
 */
public interface Identifiable {

    String getId();
}
