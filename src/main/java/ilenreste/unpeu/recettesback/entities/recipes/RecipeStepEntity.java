package ilenreste.unpeu.recettesback.entities.recipes;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One instruction in a recipe.
 * <p>
 * {@code position} is maintained by the service, which renumbers 0..n-1 from the
 * order of the incoming request on every write. {@code @OrderColumn} was
 * considered and rejected: it makes Hibernate issue extra UPDATE statements on
 * reordering and behaves badly with nulls in the collection. An explicit column
 * the service owns is boring, predictable, and readable from plain SQL when
 * debugging.
 * <p>
 * The {@code UNIQUE (recipe_id, position)} constraint also provides the index
 * for looking steps up by recipe, which is the direction those lookups go — so
 * no separate {@code recipe_id} index is needed.
 *
 * <h2>{@code instruction} holds WYSIWYG markup, and its type says so</h2>
 * {@code columnDefinition = "text"} is not decoration and must not be
 * "simplified" to a bare {@code @Column}. Hibernate's default for a
 * {@code String} is {@code varchar(255)}, and 255 characters is roughly two
 * sentences — a step like "préchauffer le four à 180 °C, beurrer un moule de 24 cm
 * et le fariner…" already spends a third of that in plain prose, and this field is
 * authored in a rich-text editor, so it arrives wrapped in markup that spends the
 * rest. The failure is also the worst kind: it appears only once a user writes a
 * long step, and surfaces as a {@code DataIntegrityViolationException} mapped to a
 * generic 500, with their text lost.
 * <p>
 * {@code text} rather than {@code @Lob}: in PostgreSQL {@code text} and
 * {@code varchar} are the same storage with the same performance, minus the length
 * check, so unbounded costs nothing. {@code @Lob} on the other hand maps to a
 * large object with a separate handle, which needs its own transaction semantics
 * to read and is the wrong tool for a string that is at most a few kilobytes.
 * {@code recipes.recommendations} and {@code recipe_introductions.text} are
 * declared the same way for the same reason.
 * <p>
 * Storing markup means it must be <strong>escaped or sanitised where it is
 * rendered</strong>. Nothing here strips it: this API records what the author
 * wrote, and a sanitiser that silently rewrote their formatting would be its own
 * bug. See {@code docs/recipes-domain-model.md}.
 */
@Getter
@Setter
@Entity
@Table(name = "recipe_steps", uniqueConstraints =
@UniqueConstraint(name = "uk_recipe_steps_position", columnNames = {"recipe_id", "position"}))
public class RecipeStepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipe_id", nullable = false)
    private RecipeEntity recipe;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, columnDefinition = "text")
    private String instruction;

    @OneToMany(mappedBy = "step", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("position")
    private Set<RecipeStepPictureEntity> pictures = new LinkedHashSet<>();
}
