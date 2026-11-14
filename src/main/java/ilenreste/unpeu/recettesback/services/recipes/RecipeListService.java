package ilenreste.unpeu.recettesback.services.recipes;

import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeListEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeListEntryId;
import ilenreste.unpeu.recettesback.services.media.MediaService;
import java.util.function.Function;
import ilenreste.unpeu.recettesback.exceptions.InvalidInputException;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.SQLException;
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

    /** PostgreSQL SQLState of a foreign key violation: the list or the recipe is gone. */
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private final RecipeListsRepository listsRepository;
    private final RecipeListEntriesRepository entriesRepository;
    private final RecipesRepository recipesRepository;
    private final UsersRepository usersRepository;
    private final CurrentUserService currentUserService;
    private final ReferenceNameNormalizer normalizer;
    private final RecipeListMapper mapper;
    private final Clock clock;
    private final MediaService mediaService;

    public RecipeListService(RecipeListsRepository listsRepository, RecipeListEntriesRepository entriesRepository,
                             RecipesRepository recipesRepository, UsersRepository usersRepository,
                             CurrentUserService currentUserService, ReferenceNameNormalizer normalizer,
                             RecipeListMapper mapper, Clock clock, MediaService mediaService) {
        this.mediaService = mediaService;
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
        RecipeListEntity list = new RecipeListEntity();
        list.setOwner(usersRepository.getReferenceById(ownerId));
        applyTo(list, request, ownerId, null);
        // Flushed here so that losing the name race to a concurrent request fails inside this method, as the
        // typed conflict the handler recognises, rather than at commit after the response has been built.
        RecipeListEntity saved = listsRepository.saveAndFlush(list);
        log.info("User {} created recipe list {}", ownerId, saved.getId());
        // A list that was just created holds nothing: no need to ask the database to count and to look for covers.
        return mapper.toResponse(saved, 0, List.of(), null);
    }

    /**
     * Replaces the name and the emoji. {@code updatedAt} moves only if one of them actually changed.
     * <p>
     * If the list is deleted from another tab between the check and the write, the UPDATE touches no row and
     * Hibernate reports it as an optimistic-locking failure. Nothing is wrong with the request, and the list is
     * simply gone: that is the 404 a retry would answer, not a 500.
     */
    @Transactional
    public RecipeListResponse update(String listId, RecipeListRequest request) {
        RecipeListEntity list = ownedList(listId);
        String ownerId = list.getOwner().getId();
        applyTo(list, request, ownerId, listId);
        try {
            RecipeListEntity saved = listsRepository.saveAndFlush(list);
            log.info("User {} updated recipe list {}", ownerId, listId);
            return summariseOne(saved);
        } catch (ObjectOptimisticLockingFailureException deletedMeanwhile) {
            log.info("Recipe list {} was deleted while user {} was renaming it", listId, ownerId);
            throw ResourceNotFoundException.of("recipe list", listId);
        }
    }

    /**
     * What create and update share: the name as typed (trimmed), its normalised form behind the uniqueness check,
     * and the emoji.
     *
     * @param ownListId the list being updated, which may keep its own normalised name, or null when creating
     */
    private void applyTo(RecipeListEntity list, RecipeListRequest request, String ownerId, String ownListId) {
        String name = request.name().trim();
        if (name.codePoints().anyMatch(RecipeListService::isControlOrLineBreak)) {
            // A NUL is not a nicety: PostgreSQL refuses 0x00 in text, which would surface as a 500 for a name sent
            // in a perfectly valid JSON string. A tab or a line break has no place in a one-line name either, and
            // U+2028 and U+2029, which are not control characters, are line breaks too.
            throw new InvalidInputException("The name must not contain control characters or line breaks.");
        }
        String normalizedName = normalizer.normalize(name);
        if (!hasVisibleCharacter(normalizedName)) {
            // Bean validation only rejects what String.isBlank() calls blank. A name made of a no-break space, a
            // zero-width space or a lone combining accent passes it, and the last one normalises to the empty string:
            // a list whose tile shows nothing, and which would then collide with the next such name.
            throw new InvalidInputException("The name must contain at least one visible character.");
        }
        requireNameFree(ownerId, normalizedName, ownListId);
        list.setName(name);
        list.setNormalizedName(normalizedName);
        list.setEmoji(request.emoji());
    }

    private static boolean isControlOrLineBreak(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
    }

    /**
     * Letters that are drawn as nothing although they are not whitespace, a separator or a format mark: the Hangul
     * fillers (U+115F, U+1160, U+3164, U+FFA0) and the blank Braille pattern (U+2800). A name made only of them is as
     * empty on a tile as one made of spaces.
     */
    private static final Set<Integer> BLANK_LOOKING = Set.of(0x115F, 0x1160, 0x3164, 0xFFA0, 0x2800);

    /**
     * Something a person can see: not whitespace, a separator, an invisible format mark (a zero-width space or joiner
     * alone shows nothing, although a joiner BETWEEN visible characters is how Persian and emoji sequences are written,
     * which is why format marks are tolerated in a name that has something else), or one of the blank-looking letters.
     */
    private static boolean hasVisibleCharacter(String text) {
        return text.codePoints().anyMatch(codePoint -> !Character.isWhitespace(codePoint)
                && !Character.isSpaceChar(codePoint)
                && Character.getType(codePoint) != Character.FORMAT
                && !BLANK_LOOKING.contains(codePoint));
    }

    /**
     * Deletes the list and, by the database's cascade, its entries. Never the recipes.
     * <p>
     * Flushed here, inside the try, because a delete is only sent at commit otherwise: two overlapping requests for the
     * same list (a double click, a retry) both pass the ownership check, the first commits, and the second's DELETE
     * touches no row. Hibernate reports that as an optimistic-locking failure, which would surface from the commit as a
     * 500. The list is gone, which is the 404 a request arriving a moment later would have been.
     */
    @Transactional
    public void delete(String listId) {
        // Loaded WITH the row lock, so a PUT /lists/{id}/cover that is attaching a photo finishes first and this
        // sees it: the photo's files can only be deleted if they are known. A second delete of the same list waits
        // here and then finds nothing, which is a 404, so no optimistic-locking catch is needed any more.
        RecipeListEntity list = ownedListForUpdate(listId);
        String ownerId = list.getOwner().getId();
        MediaEntity cover = list.getCover();
        // The list first: its row references the photo's, which cannot be deleted while it is there.
        listsRepository.delete(list);
        if (cover != null) {
            // Reads the photo's storage keys before anything is deleted, and deletes the files after commit.
            mediaService.deleteAfterCommit(cover);
        }
        log.info("User {} deleted recipe list {}", ownerId, listId);
    }

    /**
     * Puts a recipe in a list. A recipe already there is a success that changes nothing — even in a full list,
     * because nothing is being added.
     * <p>
     * The list is checked before the recipe, so a request about someone else's list never learns whether the
     * recipe exists.
     * <p>
     * {@code ON CONFLICT DO NOTHING} covers the primary key and nothing else. If the list or the recipe is deleted
     * between the checks above the insert and the insert itself, the foreign key refuses it: nothing is wrong with
     * the request, the thing it names is gone, so that is a 404 and not the 500 an unrecognised integrity violation
     * would be. Only a foreign key (SQLState 23503) is taken for that: any other integrity failure stays what it is,
     * an unexpected one, so a constraint added to this table one day cannot turn into a silent 404.
     */
    @Transactional
    public void addRecipe(String listId, String recipeId) {
        ownedList(listId);
        requireRecipe(recipeId);
        // Counted first and the existence asked only for a full list: the common add costs one query, not two.
        boolean full = entriesRepository.countByListId(listId) >= MAX_RECIPES_PER_LIST;
        if (full && !entriesRepository.existsById(new RecipeListEntryId(listId, recipeId))) {
            log.info("Recipe list {} is full: refusing recipe {}", listId, recipeId);
            throw RecipeListConflictException.listFull(MAX_RECIPES_PER_LIST);
        }
        // Atomic, so that two identical requests in flight both succeed: the check above is not what
        // guarantees "once per list", this statement is.
        int inserted;
        try {
            inserted = entriesRepository.insertIfAbsent(listId, recipeId, Instant.now(clock));
        } catch (DataIntegrityViolationException refused) {
            if (!(refused.getMostSpecificCause() instanceof SQLException cause)
                    || !FOREIGN_KEY_VIOLATION.equals(cause.getSQLState())) {
                throw refused;
            }
            log.info("Recipe list {} or recipe {} was deleted while the recipe was being added: {}", listId, recipeId,
                    cause.getMessage());
            throw new ResourceNotFoundException("The recipe list or the recipe no longer exists.");
        }
        if (inserted == 1) {
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

    /**
     * What a write to a list's photo asks first, before it spends an upload: may the caller change this list?
     *
     * @throws ResourceNotFoundException if the list does not exist or is not the caller's
     */
    @Transactional(readOnly = true)
    public void requireOwned(String listId) {
        ownedList(listId);
    }

    /**
     * The one place that decides who may <em>see</em> a list. Package-private so that the cover service of a list
     * asks the same question instead of copying it: a list's photo is visible to exactly whoever may see the list.
     */
    RecipeListEntity visibleList(String listId) {
        return load(listId, this::isVisibleTo, listsRepository::findById);
    }

    /** Who may <em>change</em> a list. Not the same question as {@link #visibleList}, and it stays owner-only. */
    RecipeListEntity ownedList(String listId) {
        return load(listId, this::isOwnedBy, listsRepository::findById);
    }

    /**
     * {@link #ownedList} with the row lock ({@code PESSIMISTIC_WRITE}), for what writes the list's photo or deletes the
     * list. Must be the first load of the list in its transaction, or the lock would return the state read before it.
     */
    RecipeListEntity ownedListForUpdate(String listId) {
        return load(listId, this::isOwnedBy, listsRepository::findWithLockById);
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
    private RecipeListEntity load(String listId, BiPredicate<RecipeListEntity, String> rule,
                                  Function<String, Optional<RecipeListEntity>> finder) {
        String callerId = callerId();
        Optional<RecipeListEntity> found = finder.apply(listId);
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

    /** The response for one list, mapped inside the transaction that loaded it. Shared with the cover service. */
    RecipeListResponse summariseOne(RecipeListEntity list) {
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
