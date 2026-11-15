package ilenreste.unpeu.recettesback.controllers.recipes;

import ilenreste.unpeu.recettesback.exceptions.RateLimitExceededException;
import ilenreste.unpeu.recettesback.exceptions.RetryableException;
import ilenreste.unpeu.recettesback.exceptions.ServiceOverloadedException;
import ilenreste.unpeu.recettesback.properties.CorsProperties;
import ilenreste.unpeu.recettesback.support.TestImages;
import org.apache.coyote.AbstractProtocol;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static ilenreste.unpeu.recettesback.support.HttpTestSupport.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * What the upload form of a list's photo leans on, checked over real HTTP against the real PostgreSQL and a real
 * storage directory under {@code target/}: how long a refusal says to wait and whether the browser can read it, what
 * happens when the member closes the form mid-upload, that every file the form lets through and the server cannot use
 * is a {@code 400} and never a {@code 500}, that a refused upload leaves the existing photo exactly as it was, and
 * the order of calls the design makes. The reasons are in docs/recipe-list-cover.md ("What the upload form relies on").
 * <p>
 * It extends the support class of the other list suites on purpose, so it runs in the same application context
 * (docs/testing-database.md: a context of its own is a connection pool of its own).
 */
@ExtendWith(OutputCaptureExtension.class)
class RecipeListCoverUploadIntegrationTest extends RecipeListsHttpSupport {

    private static final String BOUNDARY = "----recettes-hang-up-boundary";
    private static final long EIGHT_MIB = 8L * 1024 * 1024;

    @Autowired
    private CorsProperties corsProperties;
    @Autowired
    private ApplicationContext applicationContext;

    // ------------------------------------------------------------------------------------------------- helpers

    /** Everything a refused or abandoned upload must not have changed. */
    private record Snapshot(String coverVersion, String updatedAt, Set<String> files, long mediaRows, byte[] cover) {
    }

    private Snapshot snapshot(String listId) {
        JsonNode list = listOf(listId, ownerToken);
        return new Snapshot(list.path("coverVersion").asString(), list.path("updatedAt").asString(), storedFiles(),
                mediaRowsUploadedBy(owner.id()), getBytes(coverPath(listId), ownerToken).getBody());
    }

    private void assertUnchanged(String listId, Snapshot before) {
        Snapshot after = snapshot(listId);
        assertThat(after.coverVersion()).as("coverVersion").isEqualTo(before.coverVersion());
        assertThat(after.updatedAt()).as("updatedAt").isEqualTo(before.updatedAt());
        assertThat(after.files()).as("files on disk").isEqualTo(before.files());
        assertThat(after.mediaRows()).as("media rows").isEqualTo(before.mediaRows());
        assertThat(after.cover()).as("the bytes the photo is served as").isEqualTo(before.cover());
    }

    /** {@code PUT /lists/{id}/cover} with a part of any content, declared type and name, and any extra headers. */
    private ResponseEntity<String> upload(String listId, byte[] content, String filename, MediaType declaredType,
                                          HttpHeaders extra) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(declaredType);
        parts.add("file", new HttpEntity<>(new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        }, partHeaders));
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(extra);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set(HttpHeaders.AUTHORIZATION, ownerToken);
        return restTemplate.exchange(coverPath(listId), HttpMethod.PUT, new HttpEntity<>(parts, headers),
                String.class);
    }

    private ResponseEntity<String> uploadJpeg(String listId, HttpHeaders extra) {
        return upload(listId, TestImages.jpeg(300, 200), "photo.jpg", MediaType.IMAGE_JPEG, extra);
    }

    private HttpHeaders fromTheFrontEnd() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, corsProperties.allowedOrigins().getFirst());
        return headers;
    }

    private void assertAProblem(ResponseEntity<String> response, HttpStatus status) {
        assertThat(response.getStatusCode().value()).as("status of %s", response.getBody()).isEqualTo(status.value());
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(json(response).path("status").asInt()).isEqualTo(status.value());
        assertThat(detailOf(response)).isNotBlank();
    }

    /** What a browser needs to read {@code Retry-After} from a response to a cross-origin request. */
    private void assertReadableByTheBrowser(ResponseEntity<String> response) {
        assertThat(response.getHeaders().getAccessControlAllowOrigin())
                .isEqualTo(corsProperties.allowedOrigins().getFirst());
        assertThat(response.getHeaders().getAccessControlExposeHeaders()).contains(HttpHeaders.RETRY_AFTER);
    }

    private Semaphore decodePermits() {
        // Through the target, so that a proxy added around the service one day does not turn this into a null.
        Object target = AopTestUtils.getUltimateTargetObject(mediaService);
        return (Semaphore) ReflectionTestUtils.getField(target, "processingPermits");
    }

    private static void waitUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Gave up waiting after " + timeout);
            }
            Thread.sleep(25);
        }
    }

    // ------------------------------------------------------------------------ 1. what a refusal tells the front

    /**
     * The real limiter, not a stub: the allowance is spent for real, then one more is refused. The wait is whole
     * seconds inside the window (600 with the defaults), the sentence in {@code detail} says the same number, and the
     * browser can read the header from another origin.
     */
    @Test
    void theRealUploadLimit_is429_withARetryAfterInsideTheWindow_theBrowserCanRead_andNothingChanges() {
        String id = createList(unique("Photo"), ownerToken);
        for (int upload = 0; upload < mediaProperties.uploadLimit(); upload++) {
            assertThat(uploadJpeg(id, new HttpHeaders()).getStatusCode()).as("upload %d", upload + 1)
                    .isEqualTo(HttpStatus.OK);
        }
        Snapshot before = snapshot(id);

        ResponseEntity<String> refused = uploadJpeg(id, fromTheFrontEnd());

        assertAProblem(refused, HttpStatus.TOO_MANY_REQUESTS);
        long window = mediaProperties.uploadLimitWindow().toSeconds();
        long wait = Long.parseLong(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertThat(wait).as("the allowance was spent seconds ago, so nearly the whole window remains")
                .isBetween(window - 120, window);
        assertThat(detailOf(refused)).contains("Try again in " + wait + " seconds");
        assertReadableByTheBrowser(refused);
        assertUnchanged(id, before);
    }

    /**
     * The real decode pool, held in full: the request waits {@code app.media.processing-timeout} for a permit and is
     * then told to come back after exactly that long. This is the only value a 503 can carry.
     */
    @Test
    void aSaturatedDecodePool_is503_withARetryAfterOfTheProcessingTimeout_theBrowserCanRead_andNothingChanges() {
        String id = listWithCover(ownerToken);
        Snapshot before = snapshot(id);
        Semaphore permits = decodePermits();
        int held = permits.drainPermits();

        ResponseEntity<String> refused;
        try {
            refused = uploadJpeg(id, fromTheFrontEnd());
        } finally {
            permits.release(held);
        }

        assertAProblem(refused, HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo(String.valueOf(RetryableException.wholeSeconds(mediaProperties.processingTimeout())))
                .isEqualTo("10");
        assertReadableByTheBrowser(refused);
        assertThat(permits.availablePermits()).as("every permit is back").isEqualTo(mediaProperties.maxConcurrentProcessing());
        assertUnchanged(id, before);
    }

    // ------------------------------------------------------------------------- 2. the member closes the form

    /**
     * The member cancels while the file is still on its way: the server has not read the whole body, so the request
     * never reaches the controller. Nothing is stored, nothing is counted against the allowance, no decode permit is
     * taken, and the list is not touched. Hanging up with a reset and with an ordinary close are both what a
     * cancelled {@code fetch} can look like. It is not a failure of the server either, so it is not logged as one: no
     * {@code ERROR} and no stack trace for something the member did on purpose.
     */
    @ParameterizedTest(name = "reset instead of a normal close: {0}")
    @ValueSource(booleans = {true, false})
    void aClientThatHangsUpMidUpload_leavesNothing_isNotCounted_takesNoPermit_andIsNotAnError(boolean reset,
                                                                                              CapturedOutput output)
            throws Exception {
        String id = listWithCover(ownerToken);
        Snapshot before = snapshot(id);
        clearInvocations(rateLimiter, reencoder);
        byte[] body = multipartBody(TestImages.jpeg(1600, 1200));

        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoLinger(reset, 0);
            OutputStream out = socket.getOutputStream();
            out.write(requestHead(id, body.length));
            out.write(body, 0, body.length / 2);
            out.flush();
            Thread.sleep(300);
        }
        String hangUpLine = "The client hung up before it had sent the whole body of PUT " + coverPath(id);
        waitUntil(() -> output.getAll().contains(hangUpLine), Duration.ofSeconds(10));

        assertThat(output.getAll()).as("a hang-up is not logged as a failure, by the handler or by the container")
                .doesNotContain("Unhandled exception while serving PUT " + coverPath(id))
                .doesNotContain("Servlet.service()");
        assertUnchanged(id, before);
        verify(rateLimiter, never()).requirePermit(anyString());
        verify(reencoder, never()).reencode(any(), any());
        assertThat(decodePermits().availablePermits()).isEqualTo(mediaProperties.maxConcurrentProcessing());
        assertThat(putCover(id, ownerToken).getStatusCode()).as("the server is none the worse for it")
                .isEqualTo(HttpStatus.OK);
        verify(rateLimiter, times(1)).requirePermit(owner.id());
    }

    /**
     * The other way a body stops arriving: the client does not hang up, it just goes quiet (a phone that lost its signal
     * halfway, a tab that was suspended) until the server's own read timeout gives up on it. Real HTTP, real timeout: the
     * server's connection timeout is lowered for the duration of the test, on the running server and for the connection
     * this test opens, and put back (no application context of its own, which would be a connection pool of its own).
     * <p>
     * What it answers is the <strong>container's</strong> {@code 408 Request Timeout}, with Spring Boot's JSON error body
     * rather than a problem: Tomcat has already decided the request is over when the exception reaches the handler, so
     * the status is its own and nobody reads it, the client having gone. What matters is that the handler still recognises
     * it as the client's doing and logs one {@code info} line, not an {@code ERROR} with a stack trace, and that nothing
     * is stored, counted or decoded. (The document used to say {@code 400}: it was never checked.)
     */
    @Test
    void aClientThatGoesQuietMidUpload_untilTheServersReadTimesOut_is408_isLoggedAsAHangUp_andIsNotAnError(
            CapturedOutput output)
            throws Exception {
        String id = listWithCover(ownerToken);
        Snapshot before = snapshot(id);
        clearInvocations(rateLimiter, reencoder);
        byte[] body = multipartBody(TestImages.jpeg(1600, 1200));
        AbstractProtocol<?> protocol = tomcatProtocol();
        int configured = protocol.getConnectionTimeout();
        String answer;

        protocol.setConnectionTimeout(1_000);
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout((int) Duration.ofSeconds(20).toMillis());
            OutputStream out = socket.getOutputStream();
            out.write(requestHead(id, body.length));
            out.write(body, 0, body.length / 2);
            out.flush();
            // Nothing more is sent: the server waits for its read timeout and then answers.
            answer = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        } finally {
            protocol.setConnectionTimeout(configured);
        }

        assertThat(answer).as("the container's own answer to a client that went quiet").startsWith("HTTP/1.1 408");
        String hangUpLine = "The client hung up before it had sent the whole body of PUT " + coverPath(id);
        assertThat(Arrays.stream(output.getAll().split("\\R")).filter(line -> line.contains(hangUpLine)).toList())
                .as("exactly one line says so, at info").singleElement(InstanceOfAssertFactories.STRING)
                .contains(" INFO ");
        assertThat(output.getAll()).as("and none of the server's own failure")
                .doesNotContain("Unhandled exception while serving PUT " + coverPath(id))
                .doesNotContain("Servlet.service()");
        assertUnchanged(id, before);
        verify(rateLimiter, never()).requirePermit(anyString());
        verify(reencoder, never()).reencode(any(), any());
        assertThat(decodePermits().availablePermits()).isEqualTo(mediaProperties.maxConcurrentProcessing());
    }

    /** The running server's HTTP connector, for the one test that has to make its read timeout short. */
    private AbstractProtocol<?> tomcatProtocol() {
        WebServer server = ((WebServerApplicationContext) applicationContext).getWebServer();
        return (AbstractProtocol<?>) ((TomcatWebServer) server).getTomcat().getConnector().getProtocolHandler();
    }

    /**
     * The member cancels after the last byte left: the server cannot know and does not stop. The photo is attached,
     * the upload is counted once, and the decode permit it held is released. What the front must do is read the list
     * again after any cancel: the progress shown is not a safe signal of which of the two cases it was.
     */
    @Test
    void aClientThatHangsUpOnceTheBodyIsSent_stillGetsItsPhoto_countedOnce_andTheDecodePermitIsReleased()
            throws Exception {
        String id = createList(unique("Photo"), ownerToken);
        clearInvocations(rateLimiter);
        Set<String> filesBefore = storedFiles();
        CountDownLatch decoding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(decode -> {
            decoding.countDown();
            release.await(10, TimeUnit.SECONDS);
            return decode.callRealMethod();
        }).when(reencoder).reencode(any(), any());
        byte[] body = multipartBody(TestImages.jpeg(800, 600));

        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoLinger(true, 0);
            OutputStream out = socket.getOutputStream();
            out.write(requestHead(id, body.length));
            out.write(body);
            out.flush();
            assertThat(decoding.await(10, TimeUnit.SECONDS)).as("the server is decoding").isTrue();
            assertThat(decodePermits().availablePermits()).as("and holds a permit")
                    .isEqualTo(mediaProperties.maxConcurrentProcessing() - 1);
        } finally {
            // Whatever failed above, the request thread must not stay blocked in the spy while the teardown deletes
            // the list and the accounts under it.
            release.countDown();
        }
        waitUntil(() -> versionOf(id) != null, Duration.ofSeconds(10));

        assertThat(versionOf(id)).as("the abandoned request was still carried out").isNotNull();
        assertThat(mediaRowsUploadedBy(owner.id())).isEqualTo(1);
        assertThat(storedFiles()).as("two new files, compared with before so that a stray file cannot fail it")
                .hasSize(filesBefore.size() + 2).containsAll(filesBefore);
        assertThat(decodePermits().availablePermits()).isEqualTo(mediaProperties.maxConcurrentProcessing());
        verify(rateLimiter, times(1)).requirePermit(owner.id());
    }

    private static byte[] multipartBody(byte[] image) {
        byte[] head = ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"photo.jpg\""
                + "\r\nContent-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        byte[] tail = ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
        return TestImages.concat(TestImages.concat(head, image), tail);
    }

    private byte[] requestHead(String listId, int contentLength) {
        return ("PUT " + coverPath(listId) + " HTTP/1.1\r\nHost: localhost:" + port
                + "\r\nAuthorization: " + ownerToken
                + "\r\nContent-Type: multipart/form-data; boundary=" + BOUNDARY
                + "\r\nContent-Length: " + contentLength
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    // ------------------------------------------------------------------------------- 3. every 400, never a 500

    /**
     * The front checks the type and the size itself and shows one message for any {@code 400} that still comes back.
     * That only holds if none of these answers anything else.
     */
    @Test
    void everyFileTheServerCannotUse_is400AsAProblem_andNeverA500_andChangesNothing() {
        String id = listWithCover(ownerToken);
        Snapshot before = snapshot(id);
        byte[] jpeg = TestImages.jpeg(800, 600);
        byte[] png = TestImages.png(400, 300, true);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("HEIC bytes under a JPEG name and type",
                "\0\0\0\u0018ftypheic\0\0\0\0mif1heic".getBytes(StandardCharsets.ISO_8859_1));
        files.put("an empty file", new byte[0]);
        files.put("8001 px on one side", TestImages.png(8001, 10, false));
        files.put("41 megapixels", TestImages.pngHeaderClaiming(6400, 6407));
        files.put("a JPEG cut in half", Arrays.copyOf(jpeg, jpeg.length / 2));
        files.put("a JPEG that lacks only its end marker", Arrays.copyOf(jpeg, jpeg.length - 2));
        files.put("a PNG cut in half", Arrays.copyOf(png, png.length / 2));
        files.put("a JPEG signature and nothing else", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01});
        files.put("an SVG", "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8));
        files.put("a GIF", "GIF89a\u0001\0\u0001\0\0\0\0;".getBytes(StandardCharsets.ISO_8859_1));
        files.put("a WebP", "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1));

        files.forEach((name, content) -> {
            // Every one of them claims to be a JPEG, which is what a browser sends for a file it cannot tell.
            ResponseEntity<String> response = upload(id, content, "photo.jpg", MediaType.IMAGE_JPEG, new HttpHeaders());
            assertThat(response.getStatusCode()).as(name).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getHeaders().getContentType()).as(name).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(detailOf(response)).as(name).isNotBlank();
        });

        assertUnchanged(id, before);
    }

    /** A multipart request whose {@code file} is plain text, not a file part. */
    private ResponseEntity<String> uploadATextFieldNamedFile(String listId) {
        MultiValueMap<String, Object> notAFile = new LinkedMultiValueMap<>();
        notAFile.add("file", "just text, not a file part");
        return uploadParts(listId, notAFile);
    }

    /** A multipart request with a real file part, under the wrong name. */
    private ResponseEntity<String> uploadAFileNamedPhoto(String listId) {
        MultiValueMap<String, Object> otherName = new LinkedMultiValueMap<>();
        otherName.add("photo", new ByteArrayResource(TestImages.jpeg(100, 100)) {
            @Override
            public String getFilename() {
                return "photo.jpg";
            }
        });
        return uploadParts(listId, otherName);
    }

    private ResponseEntity<String> uploadParts(String listId, MultiValueMap<String, Object> parts) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set(HttpHeaders.AUTHORIZATION, ownerToken);
        return restTemplate.exchange(coverPath(listId), HttpMethod.PUT, new HttpEntity<>(parts, headers),
                String.class);
    }

    @Test
    void aMissingFilePart_andAPartWithAnotherName_are400AsAProblem() {
        String id = listWithCover(ownerToken);
        Snapshot before = snapshot(id);

        assertAProblem(uploadATextFieldNamedFile(id), HttpStatus.BAD_REQUEST);
        assertAProblem(uploadAFileNamedPhoto(id), HttpStatus.BAD_REQUEST);
        assertUnchanged(id, before);
    }

    // ---------------------------------------------------------------------- the order the gates really have

    /**
     * The shape of the request is judged while the arguments are bound, before the controller's body runs, so it is
     * judged <strong>before</strong> the list: on a list that is not the caller's, a request that is not multipart is a
     * {@code 415} and one with no {@code file} part is a {@code 400}, and only a well-formed request reaches the
     * {@code 404}. It reveals nothing about the list: it is about the caller's own request. The file is judged after
     * the list, and the rate limit is not asked anything until then.
     */
    @Test
    void theShapeOfTheRequestIsJudgedBeforeTheList_andTheFileAfterIt() {
        String someoneElses = createList(unique("Pas la mienne"), strangerToken);
        clearInvocations(rateLimiter, reencoder);

        assertAProblem(restTemplate.exchange(coverPath(someoneElses), HttpMethod.PUT,
                body("{\"file\":\"x\"}", ownerToken), String.class), HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertAProblem(uploadATextFieldNamedFile(someoneElses), HttpStatus.BAD_REQUEST);
        assertAProblem(uploadAFileNamedPhoto(someoneElses), HttpStatus.BAD_REQUEST);

        assertAProblem(uploadJpeg(someoneElses, new HttpHeaders()), HttpStatus.NOT_FOUND);
        assertAProblem(upload(someoneElses, "GIF89a\u0001\0\u0001\0\0\0\0;".getBytes(StandardCharsets.ISO_8859_1),
                "photo.jpg", MediaType.IMAGE_JPEG, new HttpHeaders()), HttpStatus.NOT_FOUND);
        verify(rateLimiter, never()).requirePermit(anyString());
        verify(reencoder, never()).reencode(any(), any());
    }

    /**
     * A file that is not an image is judged from its first bytes, before the decode pool; a picture with too many
     * pixels is judged by the decoder, which runs under the pool's permit. With the pool full the first is a
     * {@code 400} at once and the second a {@code 503}, which is what {@code POST /media} does as well.
     */
    @Test
    void aSaturatedDecodePool_answers503BeforeThe400OfAPictureWithTooManyPixels_butAfterThatOfAFileThatIsNoImage() {
        String id = listWithCover(ownerToken);
        Snapshot before = snapshot(id);
        Semaphore permits = decodePermits();
        int held = permits.drainPermits();

        ResponseEntity<String> noImage;
        ResponseEntity<String> tooManyPixels;
        try {
            noImage = upload(id, "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8),
                    "photo.jpg", MediaType.IMAGE_JPEG, new HttpHeaders());
            tooManyPixels = upload(id, TestImages.png(8001, 10, false), "wide.png", MediaType.IMAGE_PNG,
                    new HttpHeaders());
        } finally {
            permits.release(held);
        }

        assertAProblem(noImage, HttpStatus.BAD_REQUEST);
        assertAProblem(tooManyPixels, HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(upload(id, TestImages.png(8001, 10, false), "wide.png", MediaType.IMAGE_PNG, new HttpHeaders())
                .getStatusCode()).as("with the pool free again, the same picture is the 400 it always was")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertUnchanged(id, before);
    }

    /**
     * The container refuses a request over 8 MiB <em>as a whole</em> (the multipart framing counts), so a file exactly
     * 8 MiB long is refused and one a kilobyte shorter is not: it gets as far as being looked at, and fails there as
     * the 400 of a file that is not an image.
     */
    @Test
    void theSizeCap_is413AsAProblem_ForAnEightMibFile_andAKilobyteLessGetsPastTheContainer() {
        String id = listWithCover(ownerToken);
        Snapshot before = snapshot(id);

        ResponseEntity<String> tooBig = upload(id, new byte[(int) EIGHT_MIB], "huge.jpg", MediaType.IMAGE_JPEG,
                new HttpHeaders());
        ResponseEntity<String> justUnder = upload(id, new byte[(int) EIGHT_MIB - 1024], "big.jpg", MediaType.IMAGE_JPEG,
                new HttpHeaders());

        assertAProblem(tooBig, HttpStatus.CONTENT_TOO_LARGE);
        // The container does not report the limit it enforced, so the sentence cannot name it: the front has its own.
        assertThat(detailOf(tooBig)).contains("too large");
        assertThat(justUnder.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertUnchanged(id, before);
    }

    @Test
    void aRequestThatIsNotMultipart_is415AsAProblem_whetherItHasABodyOrNot() {
        String id = listWithCover(ownerToken);
        Snapshot before = snapshot(id);
        HttpHeaders bare = new HttpHeaders();
        bare.set(HttpHeaders.AUTHORIZATION, ownerToken);

        ResponseEntity<String> json = restTemplate.exchange(coverPath(id), HttpMethod.PUT,
                body("{\"file\":\"x\"}", ownerToken), String.class);
        ResponseEntity<String> empty = restTemplate.exchange(coverPath(id), HttpMethod.PUT, new HttpEntity<>(bare),
                String.class);

        assertAProblem(json, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(detailOf(json)).contains("multipart/form-data");
        assertAProblem(empty, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertUnchanged(id, before);
    }

    // ------------------------------------------------ 4. a refused upload keeps the photo it was meant to replace

    @Test
    void aRefusalFromTheLimiterOrTheDecodePool_leavesThePhotoItsVersionAndItsUpdatedAtAsTheyWere() {
        String id = listWithCover(ownerToken);
        Snapshot before = snapshot(id);
        doThrow(new RateLimitExceededException("Slow down.", Duration.ofMillis(59_400)))
                .when(rateLimiter).requirePermit(owner.id());

        ResponseEntity<String> limited = putCover(id, ownerToken);

        assertAProblem(limited, HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).as("rounded up, not down").isEqualTo("60");
        assertUnchanged(id, before);

        reset(rateLimiter);
        doThrow(new ServiceOverloadedException("The server is busy.", mediaProperties.processingTimeout()))
                .when(reencoder).reencode(any(), any());

        ResponseEntity<String> busy = putCover(id, ownerToken);

        assertAProblem(busy, HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(busy.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("10");
        assertUnchanged(id, before);
    }

    // ---------------------------------------------------------------------------------- 5. the order of calls

    @Test
    void theFlowOfTheDesign_createThenPhotoThenRenameThenRemoveTwiceThenDeleteTheList() {
        // POST /lists, then an immediate PUT on the new list.
        ResponseEntity<String> created = post("/lists", listJson(unique("Dimanche"), CAKE), ownerToken);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = json(created).path("id").asString();
        assertThat(json(created).path("coverVersion").isNull()).isTrue();

        ResponseEntity<String> put = putCover(id, ownerToken);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        String version = json(put).path("coverVersion").asString();
        assertThat(version).isNotBlank();

        // GET /lists shows the new version straight away.
        assertThat(listed(id).path("coverVersion").asString()).isEqualTo(version);

        // A later rename keeps the photo, and the photo is still served under that version.
        ResponseEntity<String> renamed = put("/lists/" + id, listJson(unique("Renommée"), PASTA), ownerToken);
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(renamed).path("coverVersion").asString()).isEqualTo(version);
        assertThat(listed(id).path("coverVersion").asString()).isEqualTo(version);
        assertThat(getBytes(coverPath(id) + "?v=" + version, ownerToken).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Removing twice answers 200 both times, and the second changes nothing.
        ResponseEntity<String> first = delete(coverPath(id), ownerToken);
        String storedAfterFirst = listed(id).path("updatedAt").asString();
        ResponseEntity<String> second = delete(coverPath(id), ownerToken);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(first).path("coverVersion").isNull()).isTrue();
        assertThat(json(second).path("coverVersion").isNull()).isTrue();
        // Compared with what is stored, not with the first answer: a write answers with the timestamp it set, to the
        // nanosecond, and PostgreSQL keeps microseconds, so the two spellings of one instant differ in the last digits.
        assertThat(json(second).path("updatedAt").asString()).isEqualTo(storedAfterFirst);
        assertThat(listed(id).path("updatedAt").asString()).isEqualTo(storedAfterFirst);

        // The list is deleted from elsewhere (another tab): all three routes are 404.
        assertThat(delete("/lists/" + id, ownerToken).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(putCover(id, ownerToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(delete(coverPath(id), ownerToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getBytes(coverPath(id), ownerToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(mediaRowsUploadedBy(owner.id())).as("and nothing is left of the photo").isZero();
    }

    private JsonNode listed(String listId) {
        for (JsonNode list : json(get("/lists", ownerToken))) {
            if (listId.equals(list.path("id").asString())) {
                return list;
            }
        }
        throw new AssertionError("List " + listId + " is not in GET /lists");
    }
}
