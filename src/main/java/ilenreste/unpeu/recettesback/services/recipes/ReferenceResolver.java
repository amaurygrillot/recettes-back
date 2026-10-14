package ilenreste.unpeu.recettesback.services.recipes;

import ilenreste.unpeu.recettesback.entities.Identifiable;
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
 * <p>
 * The type parameter is bounded by {@link Identifiable} rather than left as a
 * bare {@code T}. That is not decoration: the bound is what lets this class read
 * an id off a loaded row itself, which removed the {@code idExtractor} argument
 * every call site used to pass — five copies of {@code SomeEntity::getId}, each
 * of which could have named the wrong getter with nothing to catch it. See
 * {@link Identifiable} for why the bound is not {@code ReferenceEntity}.
 */
@Component
public class ReferenceResolver {

    /**
     * @param fieldName    the request field the ids came from, e.g. {@code "categoryIds"} — it goes
     *                     into the error message, so the caller knows where to look
     * @param requestedIds the ids to resolve; nulls and blanks are ignored, and duplicates collapse
     * @param loader       the repository's {@code findAllById}
     * @return the loaded entities keyed by id, in the order the ids were given
     * @throws InvalidReferenceException naming every id that matched no row
     */
    public <E extends Identifiable> Map<String, E> resolve(
            String fieldName, Collection<String> requestedIds,
            Function<Collection<String>, List<E>> loader) {

        Set<String> wantedIds = new LinkedHashSet<>();
        if (requestedIds != null) {
            requestedIds.stream().filter(id -> id != null && !id.isBlank()).forEach(wantedIds::add);
        }
        if (wantedIds.isEmpty()) {
            return Map.of();
        }

        Map<String, E> resolvedById = new LinkedHashMap<>();
        loader.apply(wantedIds).forEach(entity -> resolvedById.put(entity.getId(), entity));

        if (resolvedById.size() < wantedIds.size()) {
            // Report all of them at once. Failing on the first is a worse experience when a client
            // has sent a stale list and would otherwise fix one id per round trip.
            Set<String> missingIds = new LinkedHashSet<>(wantedIds);
            missingIds.removeAll(resolvedById.keySet());
            throw new InvalidReferenceException(fieldName, missingIds);
        }
        return resolvedById;
    }
}
