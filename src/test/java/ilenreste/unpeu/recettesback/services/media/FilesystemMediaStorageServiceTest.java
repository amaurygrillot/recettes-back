package ilenreste.unpeu.recettesback.services.media;

import ilenreste.unpeu.recettesback.configuration.MediaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilesystemMediaStorageServiceTest {

    @TempDir
    private Path root;

    private FilesystemMediaStorageService storage;

    private MediaProperties propertiesFor(Path storagePath) {
        return new MediaProperties(storagePath, 8000, 40_000_000L, 2000, 640,
                0.85f, 4, Duration.ofSeconds(10));
    }

    @BeforeEach
    void setUp() {
        storage = new FilesystemMediaStorageService(propertiesFor(root));
    }

    @Test
    void storesAndReadsBackTheExactBytes() throws IOException {
        byte[] content = "pixels".getBytes(StandardCharsets.UTF_8);

        storage.store("ab/cd/image.jpg", content);

        Optional<Resource> loaded = storage.load("ab/cd/image.jpg");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getContentAsByteArray()).isEqualTo(content);
        // Sharded, so no single directory accumulates every image on the server.
        assertThat(root.resolve("ab").resolve("cd").resolve("image.jpg")).exists();
    }

    @Test
    void leavesNoTemporaryFilesBehind_soAReaderNeverSeesAHalfWrittenImage() throws IOException {
        storage.store("ab/cd/image.jpg", new byte[]{1, 2, 3});

        try (var files = Files.walk(root)) {
            assertThat(files.filter(Files::isRegularFile))
                    .allSatisfy(path -> assertThat(path.getFileName().toString()).doesNotContain(".tmp"));
        }
    }

    @Test
    void createsTheRootAtStartup_ratherThanOnTheFirstUpload() {
        Path missing = root.resolve("not").resolve("created").resolve("yet");

        new FilesystemMediaStorageService(propertiesFor(missing));

        // A misconfigured storage path is an operator error, and it must surface as a red boot log
        // rather than as a 500 to the first person who tries to add a photo.
        assertThat(missing).exists();
    }

    @Test
    void refusesToStartWhenTheRootCannotBeCreated() throws IOException {
        // A regular file where a directory should be: createDirectories cannot resolve it.
        Path blocker = root.resolve("blocker");
        Files.writeString(blocker, "not a directory");

        assertThatThrownBy(() -> new FilesystemMediaStorageService(propertiesFor(blocker.resolve("under"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not usable");
    }

    @Test
    void reportsAMissingFileAsAbsent_ratherThanFailing() {
        // Restoring a database newer than the media directory leaves rows pointing at files that
        // are not there. That has to become a 404, which starts with this returning empty.
        assertThat(storage.load("ab/cd/never-written.jpg")).isEmpty();
    }

    @Test
    void reportsADirectoryAsAbsent_ratherThanReturningItAsAnImage() throws IOException {
        Files.createDirectories(root.resolve("ab/cd/looks-like-a-file.jpg"));

        assertThat(storage.load("ab/cd/looks-like-a-file.jpg")).isEmpty();
    }

    @Test
    void refusesAKeyThatEscapesTheStorageRoot() {
        // Storage keys are server-generated from a UUID and never parsed from a request, so nothing
        // should ever reach here with ../ in it. The check costs one comparison and the failure it
        // prevents is reading or overwriting arbitrary files as the application user.
        assertThatThrownBy(() -> storage.store("../../etc/passwd", new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes the storage root");

        assertThat(storage.load("../../etc/passwd")).isEmpty();
    }

    @Test
    void deleteRemovesTheFile_andIsSilentWhenItIsAlreadyGone() {
        storage.store("ab/cd/image.jpg", new byte[]{1, 2, 3});

        storage.delete("ab/cd/image.jpg");
        assertThat(storage.load("ab/cd/image.jpg")).isEmpty();

        // Runs while unwinding a failed upload, so it must never replace the real error with its own.
        assertThatCode(() -> storage.delete("ab/cd/image.jpg")).doesNotThrowAnyException();
        assertThatCode(() -> storage.delete("../escaping.jpg")).doesNotThrowAnyException();
    }

    @Test
    void reportsAFailedWriteAsAnIoFailure() {
        // A directory where the file should go: the write cannot succeed.
        storage.store("ab/cd/image.jpg", new byte[]{1});
        assertThatThrownBy(() -> storage.store("ab/cd/image.jpg/nested.jpg", new byte[]{1}))
                .isInstanceOf(UncheckedIOException.class);
    }
}
