package ilenreste.unpeu.recettesback.controllers.media;

import ilenreste.unpeu.recettesback.repositories.media.MediaRepository;
import ilenreste.unpeu.recettesback.repositories.users.RolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UserRolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
import ilenreste.unpeu.recettesback.support.TestAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The media endpoints end to end: real security chain, real re-encode, real files on disk, real
 * rows. Storage is redirected under target/ so the test never writes into the working tree.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.media.storage-path=target/test-media-integration"
)
@AutoConfigureTestRestTemplate
class MediaUploadIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private UsersRepository usersRepository;
    @Autowired
    private RolesRepository rolesRepository;
    @Autowired
    private UserRolesRepository userRolesRepository;
    @Autowired
    private MediaRepository mediaRepository;
    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    private TestAccount account;
    private String token;
    private final List<String> createdMediaIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        account = TestAccount.create(usersRepository, rolesRepository, userRolesRepository,
                passwordEncoder, "USER");
        token = account.bearerToken(restTemplate);
    }

    @AfterEach
    void tearDown() {
        createdMediaIds.forEach(mediaRepository::deleteById);
        account.close();
    }

    private HttpEntity<MultiValueMap<String, Object>> multipart(byte[] content, String filename,
                                                                MediaType declaredType, String authorization) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        Resource part = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(declaredType);
        body.add("file", new HttpEntity<>(part, partHeaders));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (authorization != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        return new HttpEntity<>(body, headers);
    }

    private String uploadJpeg(int width, int height) {
        ResponseEntity<String> response = restTemplate.postForEntity("/media",
                multipart(jpeg(width, height), "photo.jpg", MediaType.IMAGE_JPEG, token), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String id = idOf(response.getBody());
        createdMediaIds.add(id);
        return id;
    }

    @Test
    void uploadRequiresAuthentication_andRecordsTheUploader() {
        ResponseEntity<String> anonymous = restTemplate.postForEntity("/media",
                multipart(jpeg(50, 50), "photo.jpg", MediaType.IMAGE_JPEG, null), String.class);

        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String id = uploadJpeg(50, 50);
        // uploaded_by_id is set by JPA auditing from the JWT's userId claim. If AuditorAware had
        // returned empty here, this column would be null and there would be no record of who
        // uploaded what - which is the whole basis for an abuse report or a quota.
        assertThat(mediaRepository.findById(id)).hasValueSatisfying(media ->
                assertThat(media.getUploadedBy().getId()).isEqualTo(account.id()));
    }

    @Test
    void downloadIsPublic_andCarriesTheHardeningHeaders() {
        String id = uploadJpeg(50, 50);

        // No Authorization header: recipes are public, so their pictures must be too.
        ResponseEntity<byte[]> response = restTemplate.getForEntity("/media/" + id, byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        // Without nosniff, a browser may decide the response is HTML and render it in this origin.
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        // A name we generated, never the uploader's "photo.jpg".
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("inline")
                .contains(id + ".jpg")
                .doesNotContain("photo.jpg");
        // Safe only because content is immutable per id: an edit is a new upload with a new id.
        assertThat(response.getHeaders().getCacheControl()).contains("immutable").contains("max-age=31536000");
        assertThat(response.getBody()).isNotEmpty();
    }

    @Test
    void thumbnailVariantIsSmallerThanTheFullImage() {
        String id = uploadJpeg(1200, 900);

        byte[] full = restTemplate.getForObject("/media/" + id, byte[].class);
        byte[] thumb = restTemplate.getForObject("/media/" + id + "?variant=thumbnail", byte[].class);

        // The reason the variant exists: a twenty-recipe list page pulling full-size covers is a
        // multi-megabyte page to paint twenty small cards.
        assertThat(thumb.length).isLessThan(full.length / 2);
    }

    @Test
    void unknownVariantIsRejectedWithAUsefulMessage() {
        String id = uploadJpeg(50, 50);

        ResponseEntity<String> response = restTemplate.getForEntity("/media/" + id + "?variant=huge", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("thumbnail");
    }

    @Test
    void unknownIdIsNotFound() {
        ResponseEntity<String> response = restTemplate.getForEntity("/media/no-such-id", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aFileThatOnlyClaimsToBeAnImageIsRejected() {
        // Declared image/jpeg, named photo.jpg, and neither is looked at: the leading bytes decide.
        // Bland content on purpose - Tomcat spools a multipart part to a temp file, and on-access
        // antivirus quarantines a real webshell string there before the request finishes, turning
        // this into an unexplained 500. The polyglot case is covered in ImageReencoderTest, which
        // never touches the disk.
        byte[] notAnImage = "this is plain text, not an image".getBytes(StandardCharsets.UTF_8);

        ResponseEntity<String> response = restTemplate.postForEntity("/media",
                multipart(notAnImage, "photo.jpg", MediaType.IMAGE_JPEG, token), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mediaRepository.count()).isNotNegative();
    }

    @Test
    void anOversizedUploadIsRejectedBeforeAnyByteIsProcessed() {
        // spring.servlet.multipart.max-file-size is 8MB, and Tomcat enforces it before the part
        // reaches application code - which is why these bytes need not be a valid image at all.
        // 413 rather than 500 is the point: nothing broke, the file is simply too big.
        byte[] tooBig = new byte[9 * 1024 * 1024];

        ResponseEntity<String> response = restTemplate.postForEntity("/media",
                multipart(tooBig, "huge.jpg", MediaType.IMAGE_JPEG, token), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    }

    @Test
    void storedDimensionsAreThePostDownscaleOnes() {
        String id = uploadJpeg(3000, 1500);

        // app.media.max-stored-edge is 2000, so a 3000px source is stored at 2000x1000. Recording
        // 3000x1500 here would make the frontend reserve a box at 1.5x the real size.
        assertThat(mediaRepository.findById(id)).hasValueSatisfying(media -> {
            assertThat(media.getWidth()).isEqualTo(2000);
            assertThat(media.getHeight()).isEqualTo(1000);
            assertThat(media.getContentType()).isEqualTo("image/jpeg");
            assertThat(media.getChecksumSha256()).hasSize(64);
        });
    }

    private String idOf(String json) {
        int start = json.indexOf("\"id\":\"") + 6;
        return json.substring(start, json.indexOf('"', start));
    }

    private byte[] jpeg(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, Math.max(1, width / 3), Math.max(1, height / 3));
        graphics.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
