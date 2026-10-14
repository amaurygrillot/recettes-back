package ilenreste.unpeu.recettesback.services.reference;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a name as typed into the canonical form the unique constraint sits on.
 * <p>
 * One implementation shared by all four reference services, because the moment
 * two of them normalize differently the tables stop agreeing about what
 * "already exists" means.
 * <p>
 * Deliberately <strong>not</strong> normalized away: plurals. {@code oeuf} and
 * {@code oeufs} stay two rows. Stemming French correctly is a real NLP problem,
 * and getting it wrong silently merges unrelated ingredients; a human noticing a
 * duplicate and an admin merging it is the cheaper failure mode at this scale.
 */
@Component
public class ReferenceNameNormalizer {

    private static final Pattern INNER_WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    /**
     * Trim, collapse inner whitespace, lowercase, strip accents.
     * <p>
     * Accent stripping goes through {@link Normalizer.Form#NFD}, which
     * decomposes {@code é} into {@code e} plus a combining acute, and then
     * removes the combining marks — so {@code Crème} and {@code creme} collide as
     * they should.
     * <p>
     * {@link Locale#ROOT} on {@code toLowerCase} rather than the default locale:
     * under a Turkish default, {@code "I"} lowercases to a dotless {@code ı} and
     * the same name normalizes differently depending on where the server runs.
     */
    public String normalize(String name) {
        String collapsed = INNER_WHITESPACE.matcher(name.trim()).replaceAll(" ");
        String decomposed = Normalizer.normalize(collapsed, Normalizer.Form.NFD);
        return COMBINING_MARKS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
