package ilenreste.unpeu.recettesback.entities.reference;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * An open-ended facet (vegetarien, sans gluten, rapide, Noel). Optional, and
 * free to proliferate — which is the difference from {@link CategoryEntity}.
 */
@Getter
@Setter
@Entity
@Table(name = "tags")
public class TagEntity extends ReferenceEntity {
}
