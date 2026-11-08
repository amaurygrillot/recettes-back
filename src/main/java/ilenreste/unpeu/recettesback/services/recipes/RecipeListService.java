package ilenreste.unpeu.recettesback.services.recipes;

import ilenreste.unpeu.recettesback.entities.recipes.RecipeListEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeListEntryId;
import ilenreste.unpeu.recettesback.exceptions.RecipeListConflictException;
import ilenreste.unpeu.recettesback.exceptions.ResourceNotFoundException;
import ilenreste.unpeu.recettesback.mappers.recipes.RecipeListMapper;
import ilenreste.unpeu.recettesback.models.recipes.requests.RecipeListRequest;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeListResponse;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipeListEntriesRepository;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipeListEntriesRepository.EntryCount;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipeListEntriesRepository.ListCover;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipeListsRepository;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
import ilenreste.unpeu.recettesback.services.reference.ReferenceNameNormalizer;
import ilenreste.unpeu.recettesback.services.users.CurrentUserService;
import lombok.extern.log4j.Log4j2;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

/**
 * Recipe lists: a member's private shelves of recipes. The rules, and the reasons for them, are in
 * docs/recipe-lists.md.
 *
 * <h2>Who may see a list — one place</h2>
 * {@link #visibleList} decides it, through {@link #isVisibleTo}. Everything that <em>reads</em> a list goes
 * through it, including the {@code listId} filter of {@code GET /recipes} (via {@link #requireVisible}, so the
 * recipes search never inspects an owner itself). Writes go through {@link #ownedList} and
 * {@link #isOwnedBy}, deliberately a second method: today the two rules say the same thing, and when a list can
 * be made public only the first changes, because a list one may read is not a list one may edit. A list that is
 * not visible answers exactly like one that does not exist.
 *
 * <h2>What the database guarantees, and what it does not</h2>
 * The invariants are the database's: a name is unique per member (a named constraint, which the pre-check here
 * merely makes friendly), and a recipe appears once per list (the primary key, with an insert that cannot lose
 * the race). The two limits are guard rails and are checked here, which makes them best-effort under
 * concurrency: two simultaneous requests can both see 49 and both insert. See the design document for why that
 * is accepted.
 */
@Log4j2
@Service
public class RecipeListService {

    /** Lists per member. */
    public static final int MAX_LISTS_PER_MEMBER = 50;

    /** Recipes per list. */
    public static final int MAX_RECIPES_PER_LIST = 200;

    /** Pictures in the mosaic of a list tile. */
    public static final int COVER_MOSAIC_SIZE = 4;

    private final RecipeListsRepository listsRepository;
    private final RecipeListEntriesRepository entriesRepository;
    private final RecipesRepository recipesRepository;
    private final UsersRepository usersRepository;
    private final CurrentUserService currentUserService;
    private final ReferenceNameNormalizer normalizer;
    private final RecipeListMapper mapper;
    private final Clock clock;

    public RecipeListService(RecipeListsRepository listsRepository, RecipeListEntriesRepository entriesRepository,
                             RecipesRepository recipesRepository, UsersRepository usersRepository,
                             CurrentUserService currentUserService, ReferenceNameNormalizer normalizer,
                             RecipeListMapper mapper, Clock clock) {
        this.listsRepository = listsRepository;
        this.entriesRepository = entriesRepository;
        this.recipesRepository = recipesRepository;
        this.usersRepository = usersRepository;
        this.currentUserService = currentUserService;
        this.normalizer = normalizer;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * The caller's own lists, sorted by name. "Mine" is a question about ownership, not visibility, and will not
     * change when lists can be public.
     *
     * @param containing a recipe id: every item then also says whether it holds that recipe. Blank means none.
     * @throws ResourceNotFoundException if {@code containing} names no recipe
     */
    @Transactional(readOnly = true)
    public List<RecipeListResponse> list(String containing) {
        String ownerId = callerId();
        String recipeId = StringUtils.hasText(containing) ? containing : null;
        if (recipeId != null) {
            requireRecipe(recipeId);
        }
        List<RecipeListEntity> lists = listsRepository.findByOwnerIdOrderByNormalizedNameAscNameAsc(ownerId);
        Set<String> holding = recipeId == null ? null : entriesRepository.findListIdsHolding(recipeId, ownerId);
        return summarise(lists, holding);
    }

    @Transactional(readOnly = true)
    public RecipeListResponse get(String listId) {
        return summariseOne(visibleList(listId));
    }

    /**
     * What {@code GET /recipes?listId=} asks before it filters: may the caller read this list?
     *
     * @throws org.springframework.security.core.AuthenticationException if nobody is signed in (a 401)
     * @throws ResourceNotFoundException                                 if the list does not exist or is not visible
     */
    @Transactional(readOnly = true)
    public void requireVisible(String listId) {
        visibleList(listId);
    }

    @Transactional
    public RecipeListResponse create(RecipeListRequest request) {
        String ownerId = callerId();
        if (listsRepository.countByOwnerId(ownerId) >= MAX_LISTS_PER_MEMBER) {
            log.info("User {} cannot create another recipe list: they have {}", ownerId, MAX_LISTS_PER_MEMBER);
            throw RecipeListConflictException.tooManyLists(MAX_LISTS_PER_MEMBER);
        }
        String name = request.name().trim();
        String normalizedName = normalizer.normalize(name);
        requireNameFree(ownerId, normalizedName, null);

        RecipeListEntity list = new RecipeListEntity();
        list.setOwner(usersRepository.getReferenceById(ownerId));
        list.setName(name);
        list.setNormalizedName(normalizedName);
        list.setEmoji(request.emoji());
        // Flushed here so that losing the name race to a concurrent request fails inside this method, as the
        // typed conflict the handler recognises, rather than at commit after the response has been built.
        RecipeListEntity saved = listsRepository.saveAndFlush(list);
        log.info("User {} created recipe list {}", ownerId, saved.getId());
        return summariseOne(saved);
    }

    /** Replaces the name and the emoji. {@code updatedAt} moves only if one of them actually changed. */
    @Transactional
    public RecipeListResponse update(String listId, RecipeListRequest request) {
        RecipeListEntity list = ownedList(listId);
        String name = request.name().trim();
        String normalizedName = normalizer.normalize(name);
        requireNameFree(list.getOwner().getId(), normalizedName, listId);

        list.setName(name);
        list.setNormalizedName(normalizedName);
        list.setEmoji(request.emoji());
        RecipeListEntity saved = listsRepository.saveAndFlush(list);
        log.info("User {} updated recipe list {}", list.getOwner().getId(), listId);
        return summariseOne(saved);
    }

    /** Deletes the list and, by the database's cascade, its entries. Never the recipes. */
    @Transactional
    public void delete(String listId) {
        RecipeListEntity list = ownedList(listId);
        listsRepository.delete(list);
        log.info("User {} deleted recipe list {}", list.getOwner().getId(), listId);
    }

    /**
     * Puts a recipe in a list. A recipe already there is a success that changes nothing — even in a full list,
     * because nothing is being added.
     * <p>
     * The list is checked before the recipe, so a request about someone else's list never learns whether the
     * recipe exists.
     */
    @Transactional
    public void addRecipe(String listId, String recipeId) {
        ownedList(listId);
        requireRecipe(recipeId);
        boolean alreadyThere = entriesRepository.existsById(new RecipeListEntryId(listId, recipeId));
        if (!alreadyThere && entriesRepository.countByListId(listId) >= MAX_RECIPES_PER_LIST) {
            log.info("Recipe list {} is full: refusing recipe {}", listId, recipeId);
            throw RecipeListConflictException.listFull(MAX_RECIPES_PER_LIST);
        }
        // Atomic, so that two identical requests in flight both succeed: the check above is not what
        // guarantees "once per list", this statement is.
        if (entriesRepository.insertIfAbsent(listId, recipeId, Instant.now(clock)) == 1) {
            log.info("Recipe {} added to recipe list {}", recipeId, listId);
        } else {
            log.debug("Recipe {} was already in recipe list {}", recipeId, listId);
        }
    }

    /**
     * Takes a recipe out of a list; a recipe that is not there is a success. The recipe's existence is not
     * checked: one deleted in the meantime has already left every list, and a client that still shows it must
     * be able to remove it and get the answer it would have had a second earlier.
     */
    @Transactional
    public void removeRecipe(String listId, String recipeId) {
        ownedList(listId);
        if (entriesRepository.deleteEntry(listId, recipeId) == 1) {
            log.info("Recipe {} removed from recipe list {}", recipeId, listId);
        } else {
            log.debug("Recipe {} was not in recipe list {}", recipeId, listId);
        }
    }

    // ------------------------------------------------------------------------------------- who may do what

    /** The one place that decides who may <em>see</em> a list. */
    private RecipeListEntity visibleList(String listId) {
        return load(listId, this::isVisibleTo);
    }

    /** Who may <em>change</em> a list. Not the same question as {@link #visibleList}, and it stays owner-only. */
    private RecipeListEntity ownedList(String listId) {
        return load(listId, this::isOwnedBy);
    }

    private boolean isVisibleTo(RecipeListEntity list, String userId) {
        return isOwnedBy(list, userId);
    }

    private boolean isOwnedBy(RecipeListEntity list, String userId) {
        return list.getOwner().getId().equals(userId);
    }

    /**
     * Not found and not allowed answer identically — a 404 with the same text — because a 403 would confirm that
     * the id exists and is somebody's. They are logged differently: a stale id is routine, a list that exists
     * and is not the caller's is somebody probing, and the log is the only place that difference is visible.
     */
    private RecipeListEntity load(String listId, BiPredicate<RecipeListEntity, String> rule) {
        String callerId = callerId();
        Optional<RecipeListEntity> found = listsRepository.findById(listId);
        if (found.isEmpty()) {
            log.debug("User {} asked for recipe list {}, which does not exist", callerId, listId);
        } else if (!rule.test(found.get(), callerId)) {
            log.warn("User {} asked for recipe list {}, which is not theirs", callerId, listId);
            found = Optional.empty();
        }
        return found.orElseThrow(() -> ResourceNotFoundException.of("recipe list", listId));
    }

    /** Anonymous callers are a 401: {@code ApiExceptionHandler} hands the exception to the security chain. */
    private String callerId() {
        return currentUserService.currentUserId().orElseThrow(() ->
                new AuthenticationCredentialsNotFoundException("Sign in to use recipe lists."));
    }

    private void requireRecipe(String recipeId) {
        if (!recipesRepository.existsById(recipeId)) {
            throw ResourceNotFoundException.of("recipe", recipeId);
        }
    }

    /** The friendly half of the unique constraint; the constraint is what holds when two requests race. */
    private void requireNameFree(String ownerId, String normalizedName, String ownListId) {
        listsRepository.findByOwnerIdAndNormalizedName(ownerId, normalizedName)
                .filter(existing -> !existing.getId().equals(ownListId))
                .ifPresent(existing -> {
                    log.info("User {} already has recipe list {} with that name", ownerId, existing.getId());
                    throw RecipeListConflictException.nameTaken();
                });
    }

    // -------------------------------------------------------------------------------------------- mapping

    private RecipeListResponse summariseOne(RecipeListEntity list) {
        return summarise(List.of(list), null).getFirst();
    }

    /**
     * The count and the mosaic of every list, each in one statement however many lists there are.
     *
     * @param holding the ids of the lists that hold the recipe asked about, or {@code null} if none was asked
     *                about, which leaves {@code containsRecipe} out
     */
    private List<RecipeListResponse> summarise(List<RecipeListEntity> lists, Set<String> holding) {
        if (lists.isEmpty()) {
            return List.of();
        }
        List<String> ids = lists.stream().map(RecipeListEntity::getId).toList();
        Map<String, Long> counts = entriesRepository.countEntriesOf(ids).stream()
                .collect(Collectors.toMap(EntryCount::getListId, EntryCount::getRecipeCount));
        Map<String, List<String>> covers = entriesRepository.findCovers(ids, COVER_MOSAIC_SIZE).stream()
                .collect(Collectors.groupingBy(ListCover::getListId,
                        Collectors.mapping(ListCover::getMediaId, Collectors.toList())));
        return lists.stream()
                .map(list -> mapper.toResponse(list,
                        counts.getOrDefault(list.getId(), 0L),
                        covers.getOrDefault(list.getId(), List.of()),
                        holding == null ? null : holding.contains(list.getId())))
                .toList();
    }
}
