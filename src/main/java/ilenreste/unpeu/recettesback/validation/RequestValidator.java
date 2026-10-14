package ilenreste.unpeu.recettesback.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Bean validation for payloads that do not arrive through Spring MVC.
 * <p>
 * A controller gets {@code @Valid} honoured by the argument resolver; an MCP tool
 * method is invoked reflectively by the MCP server, so nothing validates its
 * arguments unless it asks. Without this, a tool would accept the empty title
 * that {@code POST /recipes} answers 400 to.
 */
@Component
public class RequestValidator {

    private final Validator validator;

    public RequestValidator(Validator validator) {
        this.validator = validator;
    }

    /** @return {@code request} unchanged, or throws {@link ConstraintViolationException} listing every violation. */
    public <T> T validated(T request) {
        Set<ConstraintViolation<T>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        return request;
    }
}
