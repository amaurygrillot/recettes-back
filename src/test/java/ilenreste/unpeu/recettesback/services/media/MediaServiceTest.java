package ilenreste.unpeu.recettesback.services.media;

import ilenreste.unpeu.recettesback.configuration.MediaProperties;
import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
import ilenreste.unpeu.recettesback.exceptions.InvalidInputException;
import ilenreste.unpeu.recettesback.exceptions.ResourceNotFoundException;
import ilenreste.unpeu.recettesback.exceptions.ServiceOverloadedException;
import ilenreste.unpeu.recettesback.mappers.media.MediaMapper;
import ilenreste.unpeu.recettesback.models.media.MediaVariant;
import ilenreste.unpeu.recettesback.models.media.responses.MediaResponse;
import ilenreste.unpeu.recettesback.repositories.media.MediaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MediaServiceTest {

    private MediaRepository mediaRepository;
    private MediaStorageService storageService;
    private MediaProperties properties;
    private MediaService mediaService;

    private MediaProperties properties(int permits, Duration timeout) {
        return new MediaProperties(Path.of("target", "test-media"), 8000, 40_000_000L,
                200, 64, 0.85f, permits, timeout);
    }

    @BeforeEach
    void setUp() {
        mediaRepository = mock(MediaRepository.class);
        storageService = mock(MediaStorageService.class);
        properties = properties(4, Duration.ofSeconds(5));
        mediaService = new MediaService(mediaRepository, storageService,
                new ImageReencoder(properties), properties, new MediaMapper());
    }

    private MultipartFile upload(byte[] content) {
        // The declared content type and filename are deliberately wrong: neither may influence
        // anything, because both are attacker-controlled.
        return new MockMultipartFile("file", "photo.jpg", "image/jpeg", content);
    }

    @Test
    void storesBothVariants_andRecordsOnlyWhatWeWrote() {
        when(mediaRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MediaResponse response = mediaService.upload(upload(TestImages.jpeg(600, 300)));

        ArgumentCaptor<MediaEntity> saved = ArgumentCaptor.forClass(MediaEntity.class);
        verify(mediaRepository).save(saved.capture());
        MediaEntity entity = saved.getValue();

        assertThat(entity.getContentType()).isEqualTo("image/jpeg");
        assertThat(entity.getWidth()).isEqualTo(200);
        assertThat(entity.getHeight()).isEqualTo(100);
        assertThat(entity.getStorageKey()).matches("[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f-]{36}\\.jpg");
        assertThat(entity.getThumbnailStorageKey()).endsWith("_thumb.jpg");
        assertThat(entity.getStorageKey()).isNotEqualTo(entity.getThumbnailStorageKey());
        assertThat(response.width()).isEqualTo(200);

        ArgumentCaptor<byte[]> stored = ArgumentCaptor.forClass(byte[].class);
        verify(storageService, org.mockito.Mockito.times(2)).store(anyString(), stored.capture());
        assertThat(entity.getSizeBytes()).isEqualTo(stored.getAllValues().getFirst().length);
        assertThat(entity.getChecksumSha256()).isEqualTo(sha256(stored.getAllValues().getFirst()));
    }

    @Test
    void rejectsAFileThatIsNotAnAllowedImage_beforeTouchingStorage() {
        MultipartFile notAnImage = upload("GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        assertThatThrownBy(() -> mediaService.upload(notAnImage))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("JPEG and PNG")
                // The HEIC hint matters: an iPhone shoots HEIC by default, so this is the error a
                // real user hits first and it has to tell them what to do about it.
                .hasMessageContaining("HEIC");

        verifyNoInteractions(storageService, mediaRepository);
    }

    @Test
    void rejectsAnEmptyUpload() {
        assertThatThrownBy(() -> mediaService.upload(upload(new byte[0])))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> mediaService.upload(null))
                .isInstanceOf(InvalidInputException.class);

        verifyNoInteractions(storageService, mediaRepository);
    }

    @Test
    void deletesBothFiles_whenTheRowCannotBeInserted() {
        // No transaction covers the filesystem, so without this every failed insert leaves two
        // orphans that nothing will ever reference or clean up.
        when(mediaRepository.save(any())).thenThrow(new IllegalStateException("constraint"));

        assertThatThrownBy(() -> mediaService.upload(upload(TestImages.jpeg(300, 300))))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<String> deleted = ArgumentCaptor.forClass(String.class);
        verify(storageService, org.mockito.Mockito.times(2)).delete(deleted.capture());
        assertThat(deleted.getAllValues()).hasSize(2)
                .anySatisfy(key -> assertThat(key).endsWith("_thumb.jpg"));
    }

    @Test
    void refusesWithRetryAfter_whenEveryProcessingPermitIsHeld() throws Exception {
        // One permit and a timeout short enough to hit: the second caller must be told to retry,
        // not queued indefinitely and not answered 500. This is what bounds peak heap when Tomcat
        // hands two hundred threads an upload each.
        MediaProperties oneAtATime = properties(1, Duration.ofMillis(50));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ImageReencoder blocking = new ImageReencoder(oneAtATime) {
            @Override
            public ReencodedImage reencode(byte[] source, ImageFormat format) {
                inside.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return super.reencode(source, format);
            }
        };
        MediaService saturated = new MediaService(mediaRepository, storageService,
                blocking, oneAtATime, new MediaMapper());
        when(mediaRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        byte[] image = TestImages.jpeg(120, 120);
        Thread holder = new Thread(() -> saturated.upload(upload(image)));
        holder.start();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            assertThatThrownBy(() -> saturated.upload(upload(image)))
                    .isInstanceOf(ServiceOverloadedException.class)
                    .hasMessageContaining("busy");
        } finally {
            release.countDown();
            holder.join(5000);
        }
    }

    @Test
    void servesTheFullVariantByDefault_andTheThumbnailWhenAsked() {
        MediaEntity media = storedMedia();
        when(mediaRepository.findById("media-1")).thenReturn(Optional.of(media));
        Resource resource = new ByteArrayResource(new byte[]{1});
        when(storageService.load(anyString())).thenReturn(Optional.of(resource));

        StoredMedia full = mediaService.load("media-1", MediaVariant.FULL);
        StoredMedia thumb = mediaService.load("media-1", MediaVariant.THUMBNAIL);

        verify(storageService).load("ab/cd/file.jpg");
        verify(storageService).load("ab/cd/file_thumb.jpg");
        assertThat(full.contentType()).isEqualTo("image/jpeg");
        // The name is generated from the media id, never from anything the uploader supplied.
        assertThat(full.filename()).isEqualTo("media-1.jpg");
        assertThat(thumb.filename()).isEqualTo("media-1.jpg");
    }

    @Test
    void reportsAnUnknownIdAsNotFound() {
        when(mediaRepository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mediaService.load("nope", MediaVariant.FULL))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(storageService, never()).load(anyString());
    }

    @Test
    void reportsARowWhoseFileIsMissingAsNotFound_notAsAServerError() {
        // The documented cost of keeping bytes outside the database: restoring a database newer
        // than the media directory leaves rows pointing at files that are not there.
        when(mediaRepository.findById("media-1")).thenReturn(Optional.of(storedMedia()));
        when(storageService.load(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mediaService.load("media-1", MediaVariant.FULL))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private MediaEntity storedMedia() {
        MediaEntity media = new MediaEntity();
        media.setId("media-1");
        media.setStorageKey("ab/cd/file.jpg");
        media.setThumbnailStorageKey("ab/cd/file_thumb.jpg");
        media.setContentType("image/jpeg");
        return media;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
