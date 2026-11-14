package ilenreste.unpeu.recettesback.controllers.recipes;

import ilenreste.unpeu.recettesback.exceptions.RateLimitExceededException;
import ilenreste.unpeu.recettesback.exceptions.ServiceOverloadedException;
import ilenreste.unpeu.recettesback.support.TestImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

import static ilenreste.unpeu.recettesback.support.HttpTestSupport.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * The photo of a recipe list, over real HTTP through the real filter chain, against the real PostgreSQL and a real
 * storage directory under {@code target/} (the shared context sets it). Every route, every status it can answer, the
 * cache versioning, the lifecycle of the row and of both files, and concurrent writes. The design is
 * docs/recipe-list-cover.md.
 * <p>
 * Two things are asserted again and again, because they are the point of the feature: that nobody but the owner ever
 * gets the bytes, and that a photo that is replaced, removed or whose list is deleted leaves neither a row nor a file.
 */
class RecipeListCoverIntegrationTest extends RecipeListsHttpSupport {

    private final List<String> categoryIds = new ArrayList<>();
    private final List<String> ingredientIds = new ArrayList<>();

    /** Runs before the superclass teardown, which removes the accounts these rows were made by. */
    @AfterEach
    void removeReferenceRows() {
        ingredientIds.forEach(id -> delete("/ingredients/" + id, adminToken));
        categoryIds.forEach(id -> delete("/categories/" + id, adminToken));
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private String coverPath(String listId) {
        return "/lists/" + listId + "/cover";
    }

    /** Creates a list and gives it a photo; returns the list id. */
    private String listWithCover(String token) {
        String id = createList(unique("Photo"), token);
        assertThat(putCover(id, token).getStatusCode()).isEqualTo(HttpStatus.OK);
        return id;
    }

    private String versionOf(String listId) {
        JsonNode version = listOf(listId, ownerToken).path("coverVersion");
        return version.isNull() ? null : version.asString();
    }

    private long mediaRowsUploadedBy(String userId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM media WHERE uploaded_by_id = ?", Long.class, userId);
        return count == null ? 0 : count;
    }

    private static String detailOf(ResponseEntity<String> response) {
        return json(response).path("detail").asString();
    }

    private static List<ResponseEntity<String>> inParallel(List<Supplier<ResponseEntity<String>>> calls) {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<String>>> futures = calls.stream()
                    .map(call -> pool.submit(() -> {
                        go.await();
                        return call.get();
                    }))
                    .toList();
            go.countDown();
            List<ResponseEntity<String>> responses = new ArrayList<>();
            for (Future<ResponseEntity<String>> future : futures) {
                responses.add(future.get());
            }
            return responses;
        } catch (InterruptedException | ExecutionException exception) {
            throw new IllegalStateException(exception);
        } finally {
            pool.shutdownNow();
        }
    }

    // --------------------------------------------------------------------------------- setting and the response

    @Test
    void putCover_answersTheUpdatedListWithAVersion_andMovesUpdatedAt() throws InterruptedException {
        String id = createList(unique("Photo"), ownerToken);
        JsonNode before = listOf(id, ownerToken);
        assertThat(before.path("coverVersion").isNull()).as("a new list has no photo").isTrue();
        Thread.sleep(20);

        ResponseEntity<String> response = putCover(id, ownerToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode list = json(response);
        assertThat(list.path("id").asString()).isEqualTo(id);
        assertThat(list.path("coverVersion").asString()).isNotBlank();
        assertThat(Instant.parse(list.path("updatedAt").asString()))
                .isAfter(Instant.parse(before.path("updatedAt").asString()));
        // The mosaic is computed whether or not the list has a photo of its own.
        assertThat(list.path("recipeCoverMediaIds").isArray()).isTrue();
        assertThat(list.has("coverMediaIds")).as("the old name is gone from the list response").isFalse();
        assertThat(versionOf(id)).isEqualTo(list.path("coverVersion").asString());
        assertThat(json(get("/lists", ownerToken)).get(0).path("coverVersion").asString())
                .isEqualTo(list.path("coverVersion").asString());
    }

    @Test
    void creatingOrRenamingAList_neverTouchesItsPhoto() {
        String id = listWithCover(ownerToken);
        String version = versionOf(id);

        ResponseEntity<String> renamed = put("/lists/" + id, listJson(unique("Renamed"), PASTA), ownerToken);

        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(renamed).path("coverVersion").asString()).isEqualTo(version);
        assertThat(versionOf(id)).isEqualTo(version);
        assertThat(json(post("/lists", listJson(unique("Other"), CAKE), ownerToken)).path("coverVersion").isNull())
                .as("a list is created without a photo").isTrue();
    }

    // ------------------------------------------------------------------------------------------------- reading

    @Test
    void getCover_servesTheBytesWithTheHeadersOfMedia_andANoCacheWithoutAVersion() {
        String id = listWithCover(ownerToken);

        ResponseEntity<byte[]> full = getBytes(coverPath(id), ownerToken);
        ResponseEntity<byte[]> thumbnail = getBytes(coverPath(id) + "?variant=thumbnail", ownerToken);

        assertThat(full.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(full.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(full.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(full.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("inline").contains(versionOf(id) + ".jpg").doesNotContain("photo.jpg");
        assertThat(full.getHeaders().getETag()).endsWith("-full\"");
        assertThat(thumbnail.getHeaders().getETag()).endsWith("-thumbnail\"");
        assertThat(full.getBody()).isNotEmpty();
        assertThat(thumbnail.getBody()).isNotEmpty();
        // Without a version it may be kept but must be revalidated, and it is never public.
        assertThat(full.getHeaders().getCacheControl()).contains("private").contains("no-cache")
                .doesNotContain("public").doesNotContain("immutable");
    }

    @Test
    void getCover_withTheCurrentVersion_isPrivateAndImmutable() {
        String id = listWithCover(ownerToken);
        String version = versionOf(id);

        ResponseEntity<byte[]> response = getBytes(coverPath(id) + "?v=" + version + "&variant=thumbnail", ownerToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).contains("private").contains("max-age=31536000")
                .contains("immutable").doesNotContain("public");
    }

    @Test
    void getCover_withABlankVersion_isNoVersion() {
        String id = listWithCover(ownerToken);

        ResponseEntity<byte[]> response = getBytes(coverPath(id) + "?v=", ownerToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).contains("no-cache").doesNotContain("immutable");
    }

    @Test
    void getCover_withAStaleVersion_is404_andNeverServedUnderALongCache() {
        String id = listWithCover(ownerToken);
        String stale = versionOf(id);
        assertThat(putCover(id, ownerToken).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<byte[]> response = getBytes(coverPath(id) + "?v=" + stale, ownerToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getCacheControl()).doesNotContain("immutable").doesNotContain("public");
        assertThat(getBytes(coverPath(id) + "?v=" + versionOf(id), ownerToken).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(getBytes(coverPath(id) + "?v=never-existed", ownerToken).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getCover_ofAListWithNoPhoto_is404() {
        String id = createList(unique("Sans photo"), ownerToken);

        assertThat(getBytes(coverPath(id), ownerToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getBytes(coverPath(id) + "?v=anything", ownerToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getCover_withAnUnknownVariant_is400() {
        String id = listWithCover(ownerToken);

        assertThat(getBytes(coverPath(id) + "?variant=huge", ownerToken).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void ifNoneMatch_answers304_repeatingThePrivateCacheControl_andOnlyForTheSameVariant() {
        String id = listWithCover(ownerToken);
        String etag = getBytes(coverPath(id), ownerToken).getHeaders().getETag();

        ResponseEntity<byte[]> unchanged = getBytes(coverPath(id), ownerToken, HttpHeaders.IF_NONE_MATCH, etag);
        ResponseEntity<byte[]> versioned = getBytes(coverPath(id) + "?v=" + versionOf(id), ownerToken,
                HttpHeaders.IF_NONE_MATCH, etag);
        ResponseEntity<byte[]> otherVariant = getBytes(coverPath(id) + "?variant=thumbnail", ownerToken,
                HttpHeaders.IF_NONE_MATCH, etag);

        assertThat(unchanged.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(unchanged.getBody()).isNull();
        assertThat(unchanged.getHeaders().getCacheControl()).contains("private").contains("no-cache")
                .doesNotContain("public");
        assertThat(versioned.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(versioned.getHeaders().getCacheControl()).contains("private").contains("immutable")
                .doesNotContain("public");
        assertThat(otherVariant.getStatusCode()).as("a tag of the full image says nothing about the thumbnail")
                .isEqualTo(HttpStatus.OK);
    }

    // ----------------------------------------------------------------------------------------------- replacing

    @Test
    void replacing_changesTheVersion_andDeletesTheOldRowAndBothOfItsFiles() {
        String id = listWithCover(ownerToken);
        String oldVersion = versionOf(id);
        Map<String, Object> old = mediaRow(oldVersion).orElseThrow();
        assertThat(fileExists((String) old.get("storage_key"))).isTrue();
        assertThat(fileExists((String) old.get("thumbnail_storage_key"))).isTrue();

        ResponseEntity<String> response = putCover(id, ownerToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String newVersion = json(response).path("coverVersion").asString();
        assertThat(newVersion).isNotEqualTo(oldVersion);
        assertThat(mediaRow(oldVersion)).as("the old row").isEmpty();
        assertThat(fileExists((String) old.get("storage_key"))).as("the old full-size file").isFalse();
        assertThat(fileExists((String) old.get("thumbnail_storage_key"))).as("the old thumbnail").isFalse();
        Map<String, Object> current = mediaRow(newVersion).orElseThrow();
        assertThat(fileExists((String) current.get("storage_key"))).isTrue();
        assertThat(fileExists((String) current.get("thumbnail_storage_key"))).isTrue();
        assertThat(mediaRowsUploadedBy(owner.id())).as("one photo, not two").isEqualTo(1);
    }

    // ----------------------------------------------------------------------------------------------- removing

    @Test
    void removing_answersTheUpdatedList_andDeletesTheRowAndBothFiles() {
        String id = listWithCover(ownerToken);
        String version = versionOf(id);
        Map<String, Object> row = mediaRow(version).orElseThrow();

        ResponseEntity<String> response = delete(coverPath(id), ownerToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(response).path("coverVersion").isNull()).isTrue();
        assertThat(mediaRow(version)).isEmpty();
        assertThat(fileExists((String) row.get("storage_key"))).isFalse();
        assertThat(fileExists((String) row.get("thumbnail_storage_key"))).isFalse();
        assertThat(getBytes(coverPath(id), ownerToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(mediaRowsUploadedBy(owner.id())).isZero();
    }

    @Test
    void removingAPhotoThereIsNone_isASuccessThatChangesNothing_updatedAtIncluded() throws InterruptedException {
        String id = createList(unique("Sans photo"), ownerToken);
        Instant before = Instant.parse(listOf(id, ownerToken).path("updatedAt").asString());
        Thread.sleep(20);

        ResponseEntity<String> response = delete(coverPath(id), ownerToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(response).path("coverVersion").isNull()).isTrue();
        assertThat(Instant.parse(json(response).path("updatedAt").asString())).isEqualTo(before);
    }

    @Test
    void removing_movesUpdatedAt() throws InterruptedException {
        String id = listWithCover(ownerToken);
        Instant before = Instant.parse(listOf(id, ownerToken).path("updatedAt").asString());
        Thread.sleep(20);

        Instant after = Instant.parse(json(delete(coverPath(id), ownerToken)).path("updatedAt").asString());

        assertThat(after).isAfter(before);
    }

    // ---------------------------------------------------------------------------------------- deleting the list

    @Test
    void deletingTheList_deletesItsPhoto_rowAndBothFiles() {
        String id = listWithCover(ownerToken);
        Map<String, Object> row = mediaRow(versionOf(id)).orElseThrow();
        Set<String> before = storedFiles();
        assertThat(before).contains((String) row.get("storage_key"), (String) row.get("thumbnail_storage_key"));

        ResponseEntity<String> response = delete("/lists/" + id, ownerToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(mediaRow((String) row.get("id"))).isEmpty();
        assertThat(storedFiles()).doesNotContain((String) row.get("storage_key"),
                (String) row.get("thumbnail_storage_key"));
        assertThat(mediaRowsUploadedBy(owner.id())).isZero();
    }

    @Test
    void deletingARecipe_doesNotTouchAnyListsPhoto() {
        String id = listWithCover(ownerToken);
        String recipeId = recipe(unique("Tarte"));
        addToList(id, recipeId, ownerToken);
        String version = versionOf(id);

        recipesRepository.deleteById(recipeId);

        assertThat(versionOf(id)).isEqualTo(version);
        assertThat(getBytes(coverPath(id) + "?v=" + version, ownerToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --------------------------------------------------------------------------------------------- privacy

    @Test
    void anotherMembersListAndAnAdministrators_are404_onAllThreeRoutes_andTheBytesAreNeverServed() {
        String id = listWithCover(ownerToken);
        String version = versionOf(id);
        String unknownDetail = detailOf(putCover("no-such-list", strangerToken));

        for (String token : List.of(strangerToken, adminToken)) {
            ResponseEntity<String> set = putCover(id, token);
            ResponseEntity<String> remove = delete(coverPath(id), token);
            ResponseEntity<byte[]> read = getBytes(coverPath(id) + "?v=" + version, token);
            ResponseEntity<byte[]> thumbnail = getBytes(coverPath(id) + "?variant=thumbnail", token);

            assertThat(set.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(remove.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(read.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(thumbnail.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(read.getBody()).as("not a byte of the photo").satisfiesAnyOf(
                    bytes -> assertThat(bytes).isNull(),
                    bytes -> assertThat(new String(bytes)).doesNotContain("JFIF"));
            // The same answer as a list that does not exist: a 404 never says that an id is somebody's.
            assertThat(detailOf(set)).isEqualTo(unknownDetail.replace("no-such-list", id));
        }
        assertThat(versionOf(id)).as("the owner's photo is untouched").isEqualTo(version);
        assertThat(getBytes(coverPath(id) + "?v=" + version, ownerToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aMatchingETagFromAnotherMember_is404_neverA304() {
        String id = listWithCover(ownerToken);
        String etag = getBytes(coverPath(id), ownerToken).getHeaders().getETag();

        ResponseEntity<byte[]> byStranger = getBytes(coverPath(id), strangerToken, HttpHeaders.IF_NONE_MATCH, etag);
        ResponseEntity<byte[]> byAdmin = getBytes(coverPath(id), adminToken, HttpHeaders.IF_NONE_MATCH, etag);

        assertThat(byStranger.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(byAdmin.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(byStranger.getHeaders().getETag()).as("no validator is revealed either").isNull();
    }

    @Test
    void everyRouteNeedsAToken_aMissingOneAndABrokenOneAre401() {
        String id = listWithCover(ownerToken);

        for (String token : new String[]{null, "Bearer not-a-token"}) {
            assertThat(sendFile(HttpMethod.PUT, coverPath(id), new byte[]{1, 2, 3}, token).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(delete(coverPath(id), token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(getBytes(coverPath(id), token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void aMissingList_is404_onAllThreeRoutes() {
        assertThat(putCover("no-such-list", ownerToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(delete(coverPath("no-such-list"), ownerToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getBytes(coverPath("no-such-list"), ownerToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getMedia_is404ForAListsPhoto_whoeverAsks_withTheAnswerOfAnUnknownId() {
        String id = listWithCover(ownerToken);
        String photoId = versionOf(id);
        String unknown = "no-such-media";
        String unknownDetail = detailOf(get("/media/" + unknown, null));

        for (String token : new String[]{ownerToken, adminToken, strangerToken, null}) {
            for (String variant : new String[]{"", "?variant=thumbnail"}) {
                ResponseEntity<String> response = get("/media/" + photoId + variant, token);
                assertThat(response.getStatusCode()).as("GET /media/%s%s", photoId, variant)
                        .isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(detailOf(response)).isEqualTo(unknownDetail.replace(unknown, photoId));
            }
        }
    }

    @Test
    void aListPhotosIdIsRefusedAsARecipePictureAnIngredientIconAndInTheirUpdates_likeAnUnknownId() {
        String photoId = versionOf(listWithCover(ownerToken));
        String categoryId = idOfNew("/categories", "{\"name\":\"%s\"}".formatted(unique("dessert")), adminToken,
                categoryIds);
        String ingredientId = idOfNew("/ingredients", "{\"name\":\"%s\"}".formatted(unique("farine")), ownerToken,
                ingredientIds);

        // A recipe's cover, then a step's picture: the same 400, naming the same field, as an id that is not there.
        for (String field : List.of("cover", "step")) {
            String known = recipeJson(field, photoId, categoryId, ingredientId);
            String unknown = recipeJson(field, "no-such-media", categoryId, ingredientId);
            ResponseEntity<String> refused = post("/recipes", known, ownerToken);
            ResponseEntity<String> unknownRefused = post("/recipes", unknown, ownerToken);

            assertThat(refused.getStatusCode()).as(field).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(unknownRefused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(detailOf(refused)).isEqualTo(detailOf(unknownRefused).replace("no-such-media", photoId));
        }

        // An ingredient's icon, created and updated.
        String icon = "{\"name\":\"%s\",\"iconMediaId\":\"%s\"}";
        ResponseEntity<String> created = post("/ingredients", icon.formatted(unique("sel"), photoId), ownerToken);
        ResponseEntity<String> createdUnknown = post("/ingredients", icon.formatted(unique("sel"), "no-such-media"),
                ownerToken);
        ResponseEntity<String> updated = put("/ingredients/" + ingredientId,
                icon.formatted(unique("sel"), photoId), adminToken);
        ResponseEntity<String> updatedUnknown = put("/ingredients/" + ingredientId,
                icon.formatted(unique("sel"), "no-such-media"), adminToken);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(detailOf(created)).isEqualTo(detailOf(createdUnknown).replace("no-such-media", photoId));
        assertThat(detailOf(updated)).isEqualTo(detailOf(updatedUnknown).replace("no-such-media", photoId));
        assertThat(mediaRow(photoId)).as("the photo is still there and still the list's").isPresent();
    }

    private String idOfNew(String path, String json, String token, List<String> cleanup) {
        ResponseEntity<String> created = post(path, json, token);
        assertThat(created.getStatusCode()).as("POST %s -> %s", path, created.getBody()).isEqualTo(HttpStatus.CREATED);
        String id = json(created).path("id").asString();
        cleanup.add(id);
        return id;
    }

    private static String recipeJson(String where, String mediaId, String categoryId, String ingredientId) {
        String cover = "cover".equals(where) ? ",\"coverPictures\":[{\"mediaId\":\"%s\"}]".formatted(mediaId) : "";
        String pictures = "step".equals(where) ? ",\"pictures\":[{\"mediaId\":\"%s\"}]".formatted(mediaId) : "";
        return """
                {"title":"Tarte","categoryIds":["%s"]%s,
                 "ingredientGroups":[{"ingredients":[{"ingredientId":"%s","quantity":1}]}],
                 "steps":[{"instruction":"Melanger"%s}]}
                """.formatted(categoryId, cover, ingredientId, pictures);
    }

    // ------------------------------------------------------------------------- the failures of POST /media

    @Test
    void aFileThatIsNotAnImage_is400_andLeavesNothing() {
        String id = createList(unique("Photo"), ownerToken);
        Set<String> files = storedFiles();

        ResponseEntity<String> text = sendFile(HttpMethod.PUT, coverPath(id), "this is plain text".getBytes(), ownerToken);
        ResponseEntity<String> heic = sendFile(HttpMethod.PUT, coverPath(id),
                "\0\0\0\u0018ftypheic\0\0\0\0mif1heic".getBytes(StandardCharsets.ISO_8859_1), ownerToken);
        ResponseEntity<String> empty = sendFile(HttpMethod.PUT, coverPath(id), new byte[0], ownerToken);

        assertThat(text.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(heic.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(detailOf(heic)).contains("HEIC").contains("JPG or PNG");
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(versionOf(id)).isNull();
        assertThat(storedFiles()).isEqualTo(files);
        assertThat(mediaRowsUploadedBy(owner.id())).isZero();
    }

    @Test
    void aPngWithTransparency_isKeptAsAPng() {
        String id = createList(unique("Photo"), ownerToken);

        ResponseEntity<String> response = sendFile(HttpMethod.PUT, coverPath(id),
                TestImages.png(120, 80, true), ownerToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getBytes(coverPath(id), ownerToken).getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
    }

    @Test
    void aMissingFilePart_is400() {
        String id = createList(unique("Photo"), ownerToken);

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("other", "x");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set(HttpHeaders.AUTHORIZATION, ownerToken);

        ResponseEntity<String> response = restTemplate.exchange(coverPath(id), HttpMethod.PUT,
                new HttpEntity<>(parts, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aPartOverEightMegabytes_is413_evenOnSomeoneElsesList() {
        String id = listWithCover(ownerToken);
        byte[] tooBig = new byte[9 * 1024 * 1024];

        for (String token : List.of(ownerToken, strangerToken)) {
            ResponseEntity<String> response = sendFile(HttpMethod.PUT, coverPath(id), tooBig, token);
            assertThat(response.getStatusCode().value()).as("413 reveals nothing about whose list it is")
                    .isEqualTo(413);
        }
    }

    @Test
    void aRequestThatIsNotMultipart_is415_onBothUploadRoutes() {
        String id = createList(unique("Photo"), ownerToken);

        ResponseEntity<String> forList = restTemplate.exchange(coverPath(id), HttpMethod.PUT,
                body("{\"file\":\"x\"}", ownerToken), String.class);
        ResponseEntity<String> forMedia = restTemplate.exchange("/media", HttpMethod.POST,
                body("{\"file\":\"x\"}", ownerToken), String.class);

        assertThat(forList.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(forMedia.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(forList.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(detailOf(forList)).contains("multipart/form-data");
    }

    @Test
    void theUploadLimit_is429WithRetryAfter_andLeavesTheOldPhotoAlone() {
        String id = listWithCover(ownerToken);
        String version = versionOf(id);
        doThrow(new RateLimitExceededException("Slow down.", Duration.ofSeconds(30)))
                .when(rateLimiter).requirePermit(owner.id());
        Set<String> files = storedFiles();

        ResponseEntity<String> response = putCover(id, ownerToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        assertThat(versionOf(id)).isEqualTo(version);
        assertThat(storedFiles()).isEqualTo(files);
    }

    @Test
    void anotherMembersList_is404BeforeTheRateLimitIsAskedAnything() {
        String id = listWithCover(ownerToken);
        clearInvocations(rateLimiter);

        assertThat(putCover(id, strangerToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(putCover(id, adminToken).getStatusCode()).as("the exemption is not a visibility bypass")
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(rateLimiter, never()).requirePermit(anyString());
    }

    @Test
    void anAdministratorIsNotRateLimitedOnHisOwnList_andANormalMemberIs() {
        String adminList = createList(unique("Admin"), adminToken);
        String ownerList = createList(unique("Owner"), ownerToken);
        clearInvocations(rateLimiter);

        assertThat(putCover(adminList, adminToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(rateLimiter, never()).requirePermit(admin.id());
        assertThat(putCover(ownerList, ownerToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(rateLimiter).requirePermit(owner.id());
    }

    @Test
    void aSaturatedDecodePool_is503WithRetryAfter_andLeavesNothing() {
        String id = createList(unique("Photo"), ownerToken);
        doThrow(new ServiceOverloadedException("The server is busy processing images. Please try again shortly.",
                Duration.ofSeconds(3))).when(reencoder).reencode(any(), any());
        Set<String> files = storedFiles();

        ResponseEntity<String> response = putCover(id, ownerToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("3");
        assertThat(versionOf(id)).isNull();
        assertThat(storedFiles()).isEqualTo(files);
        assertThat(mediaRowsUploadedBy(owner.id())).isZero();
    }

    // ------------------------------------------------------------------------------------ failures and races

    @Test
    void aFailedAttach_leavesNothingBehind_andTheOldPhotoAndItsFilesInPlace() {
        String id = listWithCover(ownerToken);
        String version = versionOf(id);
        Map<String, Object> old = mediaRow(version).orElseThrow();
        Set<String> files = storedFiles();
        // The flush that follows the swap fails: the transaction rolls back after the old files were scheduled for
        // deletion, which must therefore never run.
        doThrow(new IllegalStateException("the write failed")).when(listsRepository).flush();

        ResponseEntity<String> response = putCover(id, ownerToken);
        reset(listsRepository);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(versionOf(id)).as("still the old photo").isEqualTo(version);
        assertThat(mediaRow(version)).isPresent();
        assertThat(fileExists((String) old.get("storage_key"))).isTrue();
        assertThat(fileExists((String) old.get("thumbnail_storage_key"))).isTrue();
        assertThat(storedFiles()).as("the two new files were removed").isEqualTo(files);
        assertThat(mediaRowsUploadedBy(owner.id())).as("and no new row").isEqualTo(1);
    }

    @Test
    void aListDeletedWhileItsPhotoIsBeingUploaded_is404_andLeavesNothing() {
        String id = createList(unique("Photo"), ownerToken);
        Set<String> files = storedFiles();
        // Deleted after the ownership check and before the attach, which is when the decode runs.
        doAnswer(decode -> {
            jdbc.update("DELETE FROM recipe_lists WHERE id = ?", id);
            return decode.callRealMethod();
        }).when(reencoder).reencode(any(), any());

        ResponseEntity<String> response = putCover(id, ownerToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(storedFiles()).isEqualTo(files);
        assertThat(mediaRowsUploadedBy(owner.id())).isZero();
    }

    @Test
    void twoConcurrentPuts_bothAnswer200_andExactlyOnePhotoAndItsTwoFilesRemain() {
        String id = listWithCover(ownerToken);
        Set<String> before = storedFiles();

        List<ResponseEntity<String>> responses = inParallel(List.of(() -> putCover(id, ownerToken),
                () -> putCover(id, ownerToken)));

        assertThat(responses).extracting(ResponseEntity::getStatusCode).containsOnly(HttpStatus.OK);
        Map<String, Object> current = mediaRow(versionOf(id)).orElseThrow();
        assertThat(mediaRowsUploadedBy(owner.id())).as("exactly one photo row").isEqualTo(1);
        Set<String> after = storedFiles();
        assertThat(after).as("and exactly its two files, the others deleted")
                .hasSize(before.size())
                .contains((String) current.get("storage_key"), (String) current.get("thumbnail_storage_key"));
    }

    @Test
    void aPutRacingARemoval_endsWithAtMostOnePhoto_andNoRowOrFileLeftBehind() {
        String id = listWithCover(ownerToken);
        Set<String> before = storedFiles();

        List<ResponseEntity<String>> responses = inParallel(List.of(() -> putCover(id, ownerToken),
                () -> delete(coverPath(id), ownerToken)));

        assertThat(responses).extracting(ResponseEntity::getStatusCode).containsOnly(HttpStatus.OK);
        String version = versionOf(id);
        long expectedRows = version == null ? 0 : 1;
        assertThat(mediaRowsUploadedBy(owner.id())).isEqualTo(expectedRows);
        assertThat(storedFiles()).hasSize(before.size() - 2 + (int) (expectedRows * 2));
        if (version != null) {
            Map<String, Object> current = mediaRow(version).orElseThrow();
            assertThat(storedFiles()).contains((String) current.get("storage_key"),
                    (String) current.get("thumbnail_storage_key"));
        }
    }

    @Test
    void aPutRacingTheDeletionOfTheList_leavesNothing_whicheverWins() {
        String id = createList(unique("Photo"), ownerToken);
        Set<String> before = storedFiles();

        List<ResponseEntity<String>> responses = inParallel(List.of(() -> putCover(id, ownerToken),
                () -> delete("/lists/" + id, ownerToken)));

        assertThat(responses.get(1).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(responses.get(0).getStatusCode()).isIn(HttpStatus.OK, HttpStatus.NOT_FOUND);
        assertThat(get("/lists/" + id, ownerToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(mediaRowsUploadedBy(owner.id())).isZero();
        assertThat(storedFiles()).isEqualTo(before);
    }

    @Test
    void twoOverlappingDeletesOfAList_answerOne204AndOne404() {
        String id = listWithCover(ownerToken);

        List<ResponseEntity<String>> responses = inParallel(List.of(() -> delete("/lists/" + id, ownerToken),
                () -> delete("/lists/" + id, ownerToken)));

        assertThat(responses).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.NO_CONTENT, HttpStatus.NOT_FOUND);
        assertThat(mediaRowsUploadedBy(owner.id())).isZero();
    }

    @Test
    void aRenameRacingAPhotoWrite_cannotEraseThePhoto() {
        String id = createList(unique("Photo"), ownerToken);

        List<ResponseEntity<String>> responses = inParallel(List.of(
                () -> put("/lists/" + id, listJson(unique("Renamed"), PASTA), ownerToken),
                () -> putCover(id, ownerToken)));

        assertThat(responses).extracting(ResponseEntity::getStatusCode).containsOnly(HttpStatus.OK);
        assertThat(versionOf(id)).as("a rename writes its own columns only").isNotNull();
        assertThat(mediaRowsUploadedBy(owner.id())).isEqualTo(1);
    }
}
