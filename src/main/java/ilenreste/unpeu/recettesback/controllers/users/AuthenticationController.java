package ilenreste.unpeu.recettesback.controllers.users;

import ilenreste.unpeu.recettesback.api.AuthenticationApi;
import ilenreste.unpeu.recettesback.models.auth.AuthenticationRequest;
import ilenreste.unpeu.recettesback.models.auth.ReinitPasswordRequest;
import ilenreste.unpeu.recettesback.models.auth.ReinitPasswordRequest;
import ilenreste.unpeu.recettesback.models.users.CustomUserDetails;
import ilenreste.unpeu.recettesback.services.users.PasswordResetService;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Logging in, and asking for a password reset. The routes, parameters, status
 * codes and security of both methods are declared in {@code openapi.yaml} and
 * reach this class through the generated {@link AuthenticationApi}; see
 * {@code docs/openapi.md}.
 */
@Log4j2
@RestController
public class AuthenticationController implements AuthenticationApi {

    private final AuthenticationManager authenticationManager;
    private final JwtEncoder jwtEncoder;
    private final PasswordResetService passwordResetService;

    public AuthenticationController(AuthenticationManager authenticationManager,
                                    JwtEncoder jwtEncoder,
                                    PasswordResetService passwordResetService) {
        this.authenticationManager = authenticationManager;
        this.jwtEncoder = jwtEncoder;
        this.passwordResetService = passwordResetService;
    }

    @Override
    public ResponseEntity<String> login(AuthenticationRequest request) {

        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
        );

        CustomUserDetails userDetails = (CustomUserDetails) auth.getPrincipal();

        if (userDetails == null || userDetails.getId() == null) {
            return ResponseEntity.internalServerError().build();
        } else if (!auth.isAuthenticated() || !userDetails.isEnabled()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("self")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .subject(userDetails.getUsername())
                .claim("userId", userDetails.getId())
                .claim("roles", userDetails.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .toList())
                .build();

        String tokenValue = jwtEncoder
                .encode(JwtEncoderParameters.from(claims))
                .getTokenValue();
        return ResponseEntity.ok(tokenValue);
    }

    /**
     * Kicks off the "forgot password" flow: if the email belongs to an
     * account, an email with a reset link/token is sent. Always responds the
     * same way whether or not the email is registered, so this endpoint can't
     * be used to discover which addresses have accounts (user enumeration).
     */
    @Override
    public ResponseEntity<Void> requestPasswordReset(ReinitPasswordRequest request) {
        log.info("Password reset requested");
        passwordResetService.requestReset(request.email());
        return ResponseEntity.accepted().build();
    }

}

