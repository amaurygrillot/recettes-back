package ilenreste.unpeu.recettesback.controllers.recipes;

import ilenreste.unpeu.recettesback.exceptions.RateLimitExceededException;
import ilenreste.unpeu.recettesback.exceptions.ServiceOverloadedException;
import ilenreste.unpeu.recettesback.support.TestImages;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
@ExtendWith(OutputCaptureExtension.class)
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

    /**
     * The old photo is still in place when the list is deleted from under the request, and nothing but the new files was
     * the request's to remove: they go, and the old row and its files are left exactly as they were. (The test deletes
     * the list by SQL, which is not what the service does, so it also tidies the old photo itself.)
     */
    @Test
    void aListDeletedWhileItsPhotoIsBeingReplaced_is404_theNewFilesAreGone_andTheOldPhotoIsUntouched()
            throws Exception {
        String id = listWithCover(ownerToken);
        Map<String, Object> old = mediaRow(versionOf(id)).orElseThrow();
        Set<String> files = storedFiles();
        doAnswer(decode -> {
            jdbc.update("DELETE FROM recipe_lists WHERE id = ?", id);
            return decode.callRealMethod();
        }).when(reencoder).reencode(any(), any());

        try {
            ResponseEntity<String> response = putCover(id, ownerToken);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(storedFiles()).as("the two new files were removed, and only them").isEqualTo(files);
            assertThat(mediaRow((String) old.get("id"))).as("the old row").isPresent();
            assertThat(fileExists((String) old.get("storage_key"))).isTrue();
            assertThat(fileExists((String) old.get("thumbnail_storage_key"))).isTrue();
        } finally {
            jdbc.update("DELETE FROM media WHERE id = ?", old.get("id"));
            Files.deleteIfExists(storageRoot().resolve((String) old.get("storage_key")));
            Files.deleteIfExists(storageRoot().resolve((String) old.get("thumbnail_storage_key")));
        }
    }

    /**
     * The failure the old {@code catch} could not tell from a rollback: the transaction committed, and then the
     * deletion of the OLD files threw (impossible with the filesystem, which swallows its own failures, and exactly what
     * an object store does). The request has committed, so it is a success: the new photo is served and its two files are
     * on disk. The old row is gone, both of its files were tried, and one {@code error} line says which row.
     */
    @Test
    void aStorageThatThrowsWhenTheOldFilesAreDeleted_neverFailsThePutThatCommitted_andNeverDeletesTheNewPhoto(
            CapturedOutput output) throws Exception {
        String id = listWithCover(ownerToken);
        String oldVersion = versionOf(id);
        Map<String, Object> old = mediaRow(oldVersion).orElseThrow();
        doThrow(new IllegalStateException("the object store is down")).when(storageService).delete(anyString());

        try {
            ResponseEntity<String> response = putCover(id, ownerToken);

            assertThat(response.getStatusCode()).as("the commit succeeded: %s", response.getBody())
                    .isEqualTo(HttpStatus.OK);
            String newVersion = json(response).path("coverVersion").asString();
            assertThat(newVersion).isNotBlank().isNotEqualTo(oldVersion);
            assertThat(versionOf(id)).isEqualTo(newVersion);
            Map<String, Object> current = mediaRow(newVersion).orElseThrow();
            assertThat(fileExists((String) current.get("storage_key"))).as("the new photo's file").isTrue();
            assertThat(fileExists((String) current.get("thumbnail_storage_key"))).as("and its thumbnail").isTrue();
            assertThat(getBytes(coverPath(id) + "?v=" + newVersion, ownerToken).getStatusCode())
                    .as("the new photo is served").isEqualTo(HttpStatus.OK);
            assertThat(mediaRow(oldVersion)).as("the old row was deleted in the transaction").isEmpty();
            verify(storageService).delete((String) old.get("storage_key"));
            verify(storageService).delete((String) old.get("thumbnail_storage_key"));
            assertThat(output.getAll()).as("one error line, with the media id of the old photo and no stack trace")
                    .contains(oldVersion)
                    .doesNotContain("at ilenreste.unpeu.recettesback.services.media.MediaService");
        } finally {
            reset(storageService);
            // The deletion that failed left the old files behind, which is the point; a test must not.
            Files.deleteIfExists(storageRoot().resolve((String) old.get("storage_key")));
            Files.deleteIfExists(storageRoot().resolve((String) old.get("thumbnail_storage_key")));
        }
    }

    /** The same storage failure on the other two routes that delete a photo after commit. */
    @Test
    void aStorageThatThrowsWhenAPhotoIsRemovedOrItsListDeleted_neverFailsTheRequest() throws Exception {
        String removed = listWithCover(ownerToken);
        String deleted = listWithCover(ownerToken);
        List<Map<String, Object>> photos = List.of(mediaRow(versionOf(removed)).orElseThrow(),
                mediaRow(versionOf(deleted)).orElseThrow());
        doThrow(new IllegalStateException("the object store is down")).when(storageService).delete(anyString());

        try {
            ResponseEntity<String> removal = delete(coverPath(removed), ownerToken);
            ResponseEntity<String> deletion = delete("/lists/" + deleted, ownerToken);
            reset(storageService);

            assertThat(removal.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(json(removal).path("coverVersion").isNull()).isTrue();
            assertThat(deletion.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(get("/lists/" + deleted, ownerToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(mediaRowsUploadedBy(owner.id())).as("both rows are gone").isZero();
        } finally {
            for (Map<String, Object> photo : photos) {
                Files.deleteIfExists(storageRoot().resolve((String) photo.get("storage_key")));
                Files.deleteIfExists(storageRoot().resolve((String) photo.get("thumbnail_storage_key")));
            }
        }
    }

    /**
     * No connection for the transaction (the pool is exhausted): nothing was written to the database, so the two files
     * the request wrote have nothing to belong to and are deleted, and the old photo is untouched.
     */
    @Test
    void aTransactionThatNeverBegins_deletesTheNewFiles_andLeavesTheOldPhotoInPlace() {
        String id = listWithCover(ownerToken);
        String version = versionOf(id);
        Set<String> files = storedFiles();
        doThrow(new CannotCreateTransactionException("no connection is available")).when(transactionTemplate)
                .execute(any());

        ResponseEntity<String> response = putCover(id, ownerToken);
        reset(transactionTemplate);

        assertThat(response.getStatusCode().is5xxServerError()).as(response.getBody()).isTrue();
        assertThat(versionOf(id)).as("still the old photo").isEqualTo(version);
        assertThat(storedFiles()).as("the two new files were removed").isEqualTo(files);
        assertThat(mediaRowsUploadedBy(owner.id())).isEqualTo(1);
    }

    /**
     * The transaction committed and <em>then</em> the caller saw an exception (here, the {@code TransactionTemplate}
     * throws once the real {@code execute} has returned): the database holds the new row. Deleting the new files here
     * would leave a photo that answers {@code 404} for ever, so they are kept. The old photo, whose deletion is
     * scheduled after the commit that did happen, is gone.
     * <p>
     * This is an exception after a commit that <strong>completed</strong>, which is all it proves. A commit that fails
     * at the database is the next test.
     */
    @Test
    void anExceptionThrownAfterACommitThatCompleted_keepsTheNewFiles_becauseTheRowIsThere() {
        String id = listWithCover(ownerToken);
        String oldVersion = versionOf(id);
        Map<String, Object> old = mediaRow(oldVersion).orElseThrow();
        Set<String> before = storedFiles();
        doAnswer(commit -> {
            commit.callRealMethod();
            throw new TransactionSystemException("the connection dropped as the commit was acknowledged");
        }).when(transactionTemplate).execute(any());

        ResponseEntity<String> response = putCover(id, ownerToken);
        reset(transactionTemplate);

        assertThat(response.getStatusCode().is5xxServerError()).as(response.getBody()).isTrue();
        String newVersion = versionOf(id);
        assertThat(newVersion).as("the photo is the new one").isNotNull().isNotEqualTo(oldVersion);
        Map<String, Object> current = mediaRow(newVersion).orElseThrow();
        assertThat(fileExists((String) current.get("storage_key"))).isTrue();
        assertThat(fileExists((String) current.get("thumbnail_storage_key"))).isTrue();
        assertThat(getBytes(coverPath(id) + "?v=" + newVersion, ownerToken).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(mediaRow(oldVersion)).isEmpty();
        Set<String> expected = new HashSet<>(before);
        expected.removeAll(Set.of((String) old.get("storage_key"), (String) old.get("thumbnail_storage_key")));
        expected.addAll(Set.of((String) current.get("storage_key"), (String) current.get("thumbnail_storage_key")));
        assertThat(storedFiles()).as("the old files went, the new ones stayed").isEqualTo(expected);
    }

    /**
     * The commit itself fails at the database, after the work has returned: the PostgreSQL backend of the transaction is
     * terminated just before the {@code COMMIT} is sent, which is what a connection that drops at that instant looks like
     * from this side. Spring reports such a transaction as <em>rolled back</em> (it asserts it here, so that this test
     * cannot pass for being a different case), although with a real lost acknowledgement the server may have committed:
     * from the client the two cannot be told apart. Deleting the new files on that report is how a committed photo ends
     * up answering {@code 404}, so they are kept.
     * <p>
     * Here the server did <em>not</em> commit (the backend is gone before it reads the {@code COMMIT}), so the old photo
     * is still the list's and the two new files are strays, which is the price of the rule and is cleaned up below.
     */
    @Test
    void aCommitThatFailsAtTheDatabaseAfterTheWorkReturned_keepsTheNewFiles_eventhoughSpringCallsItARollback(
            CapturedOutput output) throws Exception {
        String id = listWithCover(ownerToken);
        String oldVersion = versionOf(id);
        Set<String> before = storedFiles();
        AtomicInteger reported = new AtomicInteger(-1);
        // The last step of the work that is spied runs inside the transaction: it registers what cuts the connection.
        doAnswer(deletion -> {
            Object real = deletion.callRealMethod();
            int backend = backendOfTheTransaction();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCompletion() {
                    terminateAndAwait(backend);
                }

                @Override
                public void afterCompletion(int status) {
                    reported.set(status);
                }
            });
            return real;
        }).when(mediaService).deleteAfterCommit(any());
        Set<String> strays = Set.of();

        try {
            ResponseEntity<String> response = putCover(id, ownerToken);
            reset(mediaService);

            assertThat(response.getStatusCode().is5xxServerError()).as(response.getBody()).isTrue();
            assertThat(reported.get()).as("what Spring reports for a commit that failed at the database")
                    .isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
            assertThat(versionOf(id)).as("the server never committed: still the old photo").isEqualTo(oldVersion);
            strays = new HashSet<>(storedFiles());
            strays.removeAll(before);
            assertThat(strays).as("the two new files were kept").hasSize(2);
            assertThat(output.getAll()).as("and a warning says whose they are, by list and by key")
                    .contains("recipe list " + id).contains(strays.iterator().next());
        } finally {
            reset(mediaService);
            for (String stray : strays) {
                Files.deleteIfExists(storageRoot().resolve(stray));
            }
        }
    }

    /** The PostgreSQL backend that runs the transaction of the request, read from inside it and without flushing. */
    private int backendOfTheTransaction() {
        return entityManager.unwrap(Session.class).doReturningWork(connection -> {
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
                rows.next();
                return rows.getInt(1);
            }
        });
    }

    /** Ends that backend from another connection, and does not return until PostgreSQL says it is gone. */
    private void terminateAndAwait(int backend) {
        jdbc.queryForObject("SELECT pg_terminate_backend(?)", Boolean.class, backend);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE pid = ?", Integer.class, backend) > 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Backend " + backend + " was still there 10 s after it was terminated");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for backend " + backend + " to go", interrupted);
            }
        }
    }

    // The overlapping writes are in RecipeListCoverConcurrencyIntegrationTest, which holds one write inside the row lock
    // to prove it.

    /**
     * The rename loads the list, a photo is attached from another connection before the rename writes, and the rename
     * must not write the old, empty {@code cover_media_id} back. Made deterministic the way the rename-versus-delete
     * race is: the name check, which runs between the load and the write, is spied, and attaches the photo itself.
     * Without {@code @DynamicUpdate} Hibernate rewrites every column and this loses the photo.
     */
    @Test
    void aRenameThatLoadedTheListBeforeAPhotoWasAttached_cannotEraseThePhoto() {
        String id = createList(unique("Photo"), ownerToken);
        String photoId = media();
        doAnswer(check -> {
            jdbc.update("UPDATE recipe_lists SET cover_media_id = ? WHERE id = ?", photoId, id);
            return Optional.empty();
        }).when(listsRepository).findByOwnerIdAndNormalizedName(owner.id(), "renamed");

        ResponseEntity<String> renamed = put("/lists/" + id, listJson("Renamed", PASTA), ownerToken);

        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(versionOf(id)).as("a rename writes its own columns only").isEqualTo(photoId);
    }

    @Test
    void theUploadRunsOutsideAnyTransaction_soNoConnectionIsHeldWhileAnImageDecodes() {
        String id = createList(unique("Photo"), ownerToken);
        AtomicBoolean transactionDuringDecode = new AtomicBoolean(true);
        doAnswer(decode -> {
            transactionDuringDecode.set(TransactionSynchronizationManager.isActualTransactionActive());
            return decode.callRealMethod();
        }).when(reencoder).reencode(any(), any());

        assertThat(putCover(id, ownerToken).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(transactionDuringDecode).as("a transaction was open while the image decoded").isFalse();
    }

    @Test
    void aStaleVersionWithAMatchingETag_is404_neverA304() {
        String id = createList(unique("Photo"), ownerToken);
        assertThat(putCover(id, ownerToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        String stale = versionOf(id);
        String etag = getBytes(coverPath(id) + "?v=" + stale, ownerToken).getHeaders().getETag();
        assertThat(putCover(id, ownerToken).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<byte[]> response = getBytes(coverPath(id) + "?v=" + stale, ownerToken,
                HttpHeaders.IF_NONE_MATCH, etag);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
