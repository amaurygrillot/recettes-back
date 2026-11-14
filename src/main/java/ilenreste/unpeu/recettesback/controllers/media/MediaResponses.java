package ilenreste.unpeu.recettesback.controllers.media;

import ilenreste.unpeu.recettesback.services.media.StoredMedia;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;

/**
 * The HTTP answer for stored image bytes, built in <strong>one place</strong>: {@code GET /media/{id}} and
 * {@code GET /lists/{id}/cover} send exactly the same headers, and differ only in the {@code Cache-Control} each
 * passes in. Four of the headers are load-bearing:
 * <ul>
 *   <li>{@code Content-Type} comes from the {@code media} row — our own recorded value, never sniffed from the
 *       file at read time.</li>
 *   <li>{@code X-Content-Type-Options: nosniff} stops a browser deciding the response is HTML and rendering it
 *       inside this origin. User-uploaded content is served from the same origin as the API, so anything that
 *       <em>did</em> render as active content would run with this origin's privileges.</li>
 *   <li>{@code Content-Disposition} carries a name <em>we</em> generated, never anything the uploader supplied.</li>
 *   <li>{@code ETag} is a strong validator built from the stored checksum, so a client that does revalidate gets a
 *       304 instead of the whole file. The 304 repeats the {@code Cache-Control} of the 200.</li>
 * </ul>
 * <strong>It decides nothing about who may read.</strong> The caller has already loaded the {@link StoredMedia}
 * through whatever rule applies — and that must come first: a conditional request is evaluated here, so a caller
 * who may not read the photo has to be refused before this runs, or a matching {@code If-None-Match} would answer
 * 304 and prove the photo exists.
 */
public final class MediaResponses {

    /** Not a constant on Spring's HttpHeaders, so it is spelled out here. */
    private static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    private MediaResponses() {
    }

    public static ResponseEntity<Resource> download(StoredMedia media, CacheControl cacheControl,
                                                    WebRequest webRequest) {
        // Sets the ETag header on the response as a side effect, whatever it returns.
        if (webRequest.checkNotModified(media.entityTag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).cacheControl(cacheControl).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(media.contentType()))
                .eTag(media.entityTag())
                .header(X_CONTENT_TYPE_OPTIONS, "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(media.filename())
                        .build()
                        .toString())
                .cacheControl(cacheControl)
                .body(media.resource());
    }
}
