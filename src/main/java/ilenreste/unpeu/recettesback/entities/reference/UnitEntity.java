package ilenreste.unpeu.recettesback.entities.reference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A unit of measure. Admin-curated by analogy with categories and tags: like
 * them it is a small closed set that shapes how every recipe reads, and unlike
 * ingredients it is not something a user legitimately needs to extend
 * mid-recipe.
 */
@Getter
@Setter
@Entity
@Table(name = "units")
public class UnitEntity extends ReferenceEntity {

    /** Short form for rendering a line, e.g. "g" for "gramme". */
    @Column
    private String abbreviation;
}
