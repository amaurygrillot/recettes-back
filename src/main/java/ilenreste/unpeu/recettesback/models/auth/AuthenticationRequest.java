package ilenreste.unpeu.recettesback.models.auth;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The body of {@code POST /auth/login}.
 * <p>
 * Both fields are <strong>required by the contract but not validated</strong>, so
 * they carry {@code @Schema(requiredMode = REQUIRED)} and not {@code @NotBlank}.
 * Login has never validated its body: a blank or missing credential is simply a
 * wrong credential and answers 401 like any other. The generated
 * {@code AuthenticationApi} puts {@code @Valid} on every request body, so a bean
 * constraint here would turn that 401 into a 400 &mdash; a behaviour change this
 * migration must not make. The annotation keeps {@code required} in the published
 * schema without introducing the validation.
 */
public record AuthenticationRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String username,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String password
) {
}
