package ilenreste.unpeu.recettesback.controllers.media;

import ilenreste.unpeu.recettesback.models.media.MediaVariant;
import ilenreste.unpeu.recettesback.models.media.responses.MediaResponse;
import ilenreste.unpeu.recettesback.services.media.MediaService;
import ilenreste.unpeu.recettesback.services.media.StoredMedia;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.time.Duration;

@Log4j2
@RequestMapping("/media")
@RestController
public class MediaController {

    /** Not a constant on Spring's HttpHeaders, so it is spelled out here. */
    private static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    /**
     * Uploads one image. Any authenticated user; the caller is recorded on the
     * row by JPA auditing.
     * <p>
     * Media is uploaded <strong>first and independently</strong>, then referenced
     * by id when a recipe is created or updated — the same "reference data must
     * exist first" rule that applies to ingredients.
     * <p>
     * There is nothing to check on the result. {@code MediaService.upload} is a
     * chain of gates — format by magic bytes, dimensions from the header,
     * re-encode, write, insert — and each one throws its own typed exception: 400
     * for a file that is not a JPEG or PNG, 413 for one over the multipart limit,
     * 503 when image processing is saturated. It also unwinds the files it wrote
     * if the row cannot be inserted, so there is no state in which this returns
     * normally and the media is not fully stored.
     */
    @PostMapping
    public ResponseEntity<MediaResponse> upload(@RequestParam("file") MultipartFile file) {
        MediaResponse created = mediaService.upload(file);
        log.info("Stored media {} ({}, {} bytes)", created.id(), created.contentType(), created.sizeBytes());
        return ResponseEntity.created(URI.create("/media/" + created.id())).body(created);
    }

    /**
     * Serves the stored bytes. Public, since recipes are public.
     * <p>
     * Five things about this response are load-bearing:
     * <ul>
     *   <li>{@code Content-Type} comes from the {@code media} row — our own
     *       recorded value, never sniffed from the file at read time.</li>
     *   <li>{@code X-Content-Type-Options: nosniff} stops a browser deciding the
     *       response is HTML and rendering it inside this origin. User-uploaded
     *       content is served from the same origin as the API, so anything that
     *       <em>did</em> render as active content would run with this origin's
     *       privileges. Re-encoding plus the SVG ban plus this header is what
     *       closes that; a separate {@code media.} subdomain is the belt-and-
     *       braces version, worth doing once the VPS has its DNS and TLS.</li>
     *   <li>{@code Content-Disposition} carries a name <em>we</em> generated,
     *       never anything the uploader supplied.</li>
     *   <li>The year-long {@code immutable} cache is safe only because content is
     *       immutable per id — see below.</li>
     *   <li>{@code ETag} is a strong validator built from the stored checksum, so
     *       a client that does revalidate gets a 304 instead of the whole file.</li>
     * </ul>
     * A row whose file is missing answers 404, not 500 — restoring a database
     * newer than the media directory is a real operational case.
     *
     * <h2>"What if the image is updated? Can the cache be cleared?"</h2>
     * It cannot be, and it never needs to be, because <strong>nothing can update
     * the bytes behind an id</strong>. There is no {@code PUT /media/{id}} and no
     * replace path: editing a picture means {@code POST /media} again, which mints
     * a new id, and then pointing the recipe at that new id. The old URL keeps
     * serving the old bytes, correctly, for as long as anything still references
     * it. That is what makes {@code immutable} an honest header here rather than
     * an optimistic one — and it is the reason it must not be copied onto a
     * resource that <em>can</em> change, where a year-long cache would be
     * unfixable from the server side.
     * <p>
     * The {@code ETag} is the safety net for the gap that does exist: an
     * intermediary or a client that ignores {@code immutable} — a forced reload
     * does exactly that — would otherwise re-download several megabytes to
     * discover nothing changed. {@code checkNotModified} compares it against
     * {@code If-None-Match} and answers 304 with no body when they agree.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Resource> download(@PathVariable String id,
                                             @RequestParam(defaultValue = "full") String variant,
                                             WebRequest webRequest) {
        StoredMedia media = mediaService.load(id, MediaVariant.from(variant));

        // Sets the ETag header on the response as a side effect, whatever it returns.
        if (webRequest.checkNotModified(media.entityTag())) {
            log.debug("Media {} ({}) is unchanged for this caller", id, variant);
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).build();
        }
        log.debug("Serving media {} ({})", id, variant);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(media.contentType()))
                .eTag(media.entityTag())
                .header(X_CONTENT_TYPE_OPTIONS, "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(media.filename())
                        .build()
                        .toString())
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .body(media.resource());
    }
}
