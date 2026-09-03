package ilenreste.unpeu.recettesback.services.recipes;

import ilenreste.unpeu.recettesback.exceptions.InvalidReferenceException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Turns a body full of ids into loaded entities, in <strong>one</strong> query
 * per reference table.
 * <p>
 * {@code POST /recipes} carries many ids: category ids, tag ids, one ingredient
 * id per line, a unit id per line, media ids. A
 * {@code findById().orElseThrow()} per id is 30+ queries on a normal recipe, and
 * it fails on the first bad id rather than telling the caller about all of them.
 * <p>
 * The pattern is identical at all five call sites, which is why it lives here
 * rather than being written out five times: collect the distinct ids, load them
 * once, and if fewer rows came back, subtract to find which ones are missing and
 * name them. That is what makes the resulting 400 actionable instead of just
 * "bad request".
 */
@Component
public class ReferenceResolver {

    /**
     * @param field        the request field the ids came from, e.g. {@code "categoryIds"} — it goes
     *                     into the error message, so the caller knows where to look
     * @param ids          the ids to resolve; nulls and blanks are ignored, and duplicates collapse
     * @param loader       the repository's {@code findAllById}
     * @param idExtractor  how to read an id back off a loaded entity
     * @return the loaded entities keyed by id, in the order the ids were given
     * @throws InvalidReferenceException naming every id that matched no row
     */
    public <T> Map<String, T> resolve(String field, Collection<String> ids,
                                      Function<Collection<String>, List<T>> loader,
                                      Function<T, String> idExtractor) {
        Set<String> wanted = new LinkedHashSet<>();
        if (ids != null) {
            ids.stream().filter(id -> id != null && !id.isBlank()).forEach(wanted::add);
        }
        if (wanted.isEmpty()) {
            return Map.of();
        }

        Map<String, T> found = new LinkedHashMap<>();
        loader.apply(wanted).forEach(entity -> found.put(idExtractor.apply(entity), entity));

        if (found.size() < wanted.size()) {
            // Report all of them at once. Failing on the first is a worse experience when a client
            // has sent a stale list and would otherwise fix one id per round trip.
            Set<String> missing = new LinkedHashSet<>(wanted);
            missing.removeAll(found.keySet());
            throw new InvalidReferenceException(field, missing);
        }
        return found;
    }
}
