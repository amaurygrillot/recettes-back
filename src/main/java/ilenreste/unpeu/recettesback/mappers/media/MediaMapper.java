package ilenreste.unpeu.recettesback.mappers.media;

import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
import ilenreste.unpeu.recettesback.models.media.responses.MediaResponse;
import org.springframework.stereotype.Component;

/**
 * Translates a {@link MediaEntity} into what the API exposes.
 * <p>
 * Note what does not cross: {@code storageKey} and {@code thumbnailStorageKey}
 * describe where bytes sit on our disk and are never sent to a client.
 */
@Component
public class MediaMapper {

    public MediaResponse toResponse(MediaEntity media) {
        return new MediaResponse(
                media.getId(),
                media.getContentType(),
                media.getWidth(),
                media.getHeight(),
                media.getSizeBytes()
        );
    }
}
