package ilenreste.unpeu.recettesback.services.media;

/**
 * What the upload pipeline leaves on disk: two files written under generated keys, and everything a {@code media}
 * row will record about them. It is <strong>not</strong> a row, and nothing refers to it yet: whoever called
 * {@link MediaService#intake} decides what the files become — {@code POST /media} makes a public row, a list's
 * photo is attached in the transaction of its list — and must {@link MediaService#discard discard} the files if it
 * cannot.
 * <p>
 * Every field describes the file that was stored, never the file that was uploaded; see {@code MediaEntity}.
 *
 * @param storageKey          key of the full-size file
 * @param thumbnailStorageKey key of the thumbnail
 * @param format              the format <em>we</em> chose, which decides the recorded content type
 * @param width               width of the stored full-size image
 * @param height              height of the stored full-size image
 * @param sizeBytes           size of the stored full-size file
 * @param checksumSha256      of the stored full-size bytes
 */
public record StoredImage(String storageKey, String thumbnailStorageKey, ImageFormat format,
                          int width, int height, long sizeBytes, String checksumSha256) {
}
