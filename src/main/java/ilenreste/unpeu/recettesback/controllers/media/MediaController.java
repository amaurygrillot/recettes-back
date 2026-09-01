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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
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
     */
    @PostMapping
    public ResponseEntity<MediaResponse> upload(@RequestParam("file") MultipartFile file) {
        MediaResponse created = mediaService.upload(file);
        return ResponseEntity.created(URI.create("/media/" + created.id())).body(created);
    }

    /**
     * Serves the stored bytes. Public, since recipes are public.
     * <p>
     * Four things about this response are load-bearing:
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
     *       immutable per id: an edited picture is a new upload with a new id,
     *       never a rewrite of an existing one.</li>
     * </ul>
     * A row whose file is missing answers 404, not 500 — restoring a database
     * newer than the media directory is a real operational case.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Resource> download(@PathVariable String id,
                                             @RequestParam(defaultValue = "full") String variant) {
        StoredMedia media = mediaService.load(id, MediaVariant.from(variant));
        log.debug("Serving media {} ({})", id, variant);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(media.contentType()))
                .header(X_CONTENT_TYPE_OPTIONS, "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(media.filename())
                        .build()
                        .toString())
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .body(media.resource());
    }
}
