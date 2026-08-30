package ilenreste.unpeu.recettesback.controllers;

import ilenreste.unpeu.recettesback.models.auth.AuthenticationRequest;
import ilenreste.unpeu.recettesback.models.auth.ReinitPasswordRequest;
import ilenreste.unpeu.recettesback.models.users.CustomUserDetails;
import ilenreste.unpeu.recettesback.services.PasswordResetService;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@Log4j2
@RequestMapping("/auth")
@RestController
public class AuthenticationController {

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

    @PostMapping("/login")
    public ResponseEntity<String> login(@RequestBody AuthenticationRequest authenticationRequest) {

        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        authenticationRequest.username(), authenticationRequest.password()
                )
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
    @PostMapping("/reinit-password")
    public ResponseEntity<Void> reinitPassword(@Valid @RequestBody ReinitPasswordRequest request) {
        try {
            passwordResetService.requestReset(request.email());
        } catch (Exception exception) {
            log.error("Error while requesting password reset", exception);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        return ResponseEntity.accepted().build();
    }

}

