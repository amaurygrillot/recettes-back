package ilenreste.unpeu.recettesback.services.recipes;

import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeListEntity;
import ilenreste.unpeu.recettesback.exceptions.ResourceNotFoundException;
import ilenreste.unpeu.recettesback.models.media.MediaVariant;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeListResponse;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipeListsRepository;
import ilenreste.unpeu.recettesback.services.media.MediaService;
import ilenreste.unpeu.recettesback.services.media.StoredImage;
import ilenreste.unpeu.recettesback.services.media.StoredMedia;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * The photo of a recipe list: set or replace it, remove it, read it. The rules, and the reasons for them, are in
 * docs/recipe-list-cover.md.
 *
 * <h2>Who may do what — the lists' rule, not a copy of it</h2>
 * Every method asks {@link RecipeListService} — {@code visibleList} to read, {@code ownedList} and
 * {@code ownedListForUpdate} to write — so that a list's photo is visible to exactly whoever may see the list, and
 * the day a list can be public its photo follows with no change here. A list that is missing and a list that is not
 * the caller's answer identically.
 *
 * <h2>Transactions: none across the upload</h2>
 * {@link #replace} is deliberately <strong>not</strong> {@code @Transactional}. It checks the list in a short read
 * transaction, runs the shared upload pipeline with <strong>no database connection held</strong> (a decode, and the
 * wait for the processing pool, can take seconds), and only then opens the short write transaction that attaches the
 * photo. That transaction is a {@link TransactionTemplate}, not a second annotated method of this class, because a
 * call from one method of a bean to another does not cross the proxy and would run with no transaction at all.
 *
 * <h2>The lock</h2>
 * The write transaction loads the list again {@code WITH A ROW LOCK}, reads the photo it holds <em>now</em>, swaps
 * it and schedules the old files for deletion after commit. Every write to the photo, and the deletion of the list,
 * takes that lock, which serialises them: the last to commit wins, and whatever is not the final photo is deleted,
 * row and files.
 */
@Log4j2
@Service
public class RecipeListCoverService {

    private final RecipeListService lists;
    private final RecipeListsRepository listsRepository;
    private final MediaService mediaService;
    private final TransactionTemplate transactions;

    public RecipeListCoverService(RecipeListService lists, RecipeListsRepository listsRepository,
                                  MediaService mediaService, TransactionTemplate transactions) {
        this.lists = lists;
        this.listsRepository = listsRepository;
        this.mediaService = mediaService;
        this.transactions = transactions;
    }

    /**
     * Sets the list's photo, or replaces it, and answers the updated list.
     * <p>
     * The gates, in order: the list's ownership (404, not counted against the rate limit), then the shared pipeline
     * ({@link MediaService#intake}: the rate limit 429, the format and dimensions 400, the decode pool 503), then
     * the attach. If the attach fails for any reason, the two new files are deleted and the old photo is left
     * alone: a failed request leaves nothing behind. A list deleted in the meantime is a 404.
     */
    public RecipeListResponse replace(String listId, MultipartFile file) {
        lists.requireOwned(listId);
        StoredImage image = mediaService.intake(file);
        try {
            return transactions.execute(status -> attach(listId, image));
        } catch (RuntimeException failure) {
            // The row was never committed: the transaction rolled it back. Only the files are left to remove.
            mediaService.discard(image);
            throw failure;
        }
    }

    private RecipeListResponse attach(String listId, StoredImage image) {
        RecipeListEntity list = lists.ownedListForUpdate(listId);
        MediaEntity previous = list.getCover();
        list.setCover(mediaService.persist(image));
        if (previous != null) {
            mediaService.deleteAfterCommit(previous);
        }
        // Flushed here so that a failure surfaces inside the transaction, before the answer is built.
        listsRepository.flush();
        if (previous == null) {
            log.info("Recipe list {} was given a photo", listId);
        } else {
            log.info("The photo of recipe list {} was replaced", listId);
        }
        return lists.summariseOne(list);
    }

    /**
     * Removes the list's photo, row and files, and answers the updated list. A list with no photo is a success that
     * changes nothing: no write, and {@code updatedAt} stays.
     */
    @Transactional
    public RecipeListResponse remove(String listId) {
        RecipeListEntity list = lists.ownedListForUpdate(listId);
        MediaEntity previous = list.getCover();
        if (previous == null) {
            log.debug("Recipe list {} has no photo to remove", listId);
            return lists.summariseOne(list);
        }
        list.setCover(null);
        mediaService.deleteAfterCommit(previous);
        listsRepository.flush();
        log.info("The photo of recipe list {} was removed", listId);
        return lists.summariseOne(list);
    }

    /**
     * The photo's bytes, for whoever may <em>see</em> the list: the same rule as {@code GET /lists/{id}}, applied
     * <strong>before</strong> anything about the photo is revealed, so another member learns nothing — not that it
     * exists, not that a tag matches.
     *
     * @param version the {@code coverVersion} the caller read from the list, or blank for none
     * @throws ResourceNotFoundException if the list is missing or not visible, if it has no photo, if
     *                                   {@code version} is not the current one (that version no longer exists, and is
     *                                   never served), or if the file is gone
     */
    @Transactional(readOnly = true)
    public StoredMedia load(String listId, MediaVariant variant, String version) {
        RecipeListEntity list = lists.visibleList(listId);
        String current = list.coverVersion();
        if (current == null) {
            throw new ResourceNotFoundException("That recipe list has no photo.");
        }
        if (StringUtils.hasText(version) && !version.equals(current)) {
            log.debug("Recipe list {} was asked for version {} of its photo, which is not the current one",
                    listId, version);
            throw new ResourceNotFoundException("That version of the photo no longer exists.");
        }
        return mediaService.open(list.getCover(), variant);
    }
}
