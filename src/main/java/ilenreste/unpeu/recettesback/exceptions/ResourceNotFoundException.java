package ilenreste.unpeu.recettesback.exceptions;

/**
 * The resource named in the request <em>path</em> does not exist — mapped to
 * {@code 404 Not Found}.
 * <p>
 * Deliberately distinct from {@link InvalidReferenceException}: a bad id inside
 * a request body is invalid input, not a missing endpoint. See
 * {@code docs/api-error-handling.md}.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    /**
     * @param resource human-readable resource name, e.g. {@code "recipe"}
     * @param id       the id that was looked up
     */
    public static ResourceNotFoundException of(String resource, String id) {
        return new ResourceNotFoundException("No %s with id %s".formatted(resource, id));
    }
}
