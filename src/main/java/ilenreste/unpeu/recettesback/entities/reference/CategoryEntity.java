package ilenreste.unpeu.recettesback.entities.reference;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A taxonomy entry: a small, stable, mostly-disjoint set (entrees, plats,
 * desserts) that drives the main navigation. Every recipe must carry at least
 * one, which is what makes every recipe browsable.
 * <p>
 * Structurally identical to {@link TagEntity}; the difference is a product
 * decision enforced by convention and by the {@code @NotEmpty} on
 * {@code categoryIds}, not by the schema. If the category list ever grows to
 * twenty entries with recipes carrying five each, that is the signal the two
 * have merged in practice and one of them should go.
 */
@Getter
@Setter
@Entity
@Table(name = "categories")
public class CategoryEntity extends ReferenceEntity {
}
