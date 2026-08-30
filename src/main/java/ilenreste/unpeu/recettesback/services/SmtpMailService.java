package ilenreste.unpeu.recettesback.services;

import lombok.extern.log4j.Log4j2;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Log4j2
@Service
public class SmtpMailService implements MailService {

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String resetPasswordUrl;

    public SmtpMailService(
            JavaMailSender mailSender,
            @Value("${app.mail.from}") String fromAddress,
            @Value("${app.password-reset.frontend-reset-url}") String resetPasswordUrl
    ) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.resetPasswordUrl = resetPasswordUrl;
    }

    @Override
    public void sendPasswordResetEmail(String toEmail, String resetToken) {
        String link = UriComponentsBuilder.fromUriString(resetPasswordUrl)
                .queryParam("token", resetToken)
                .queryParam("email", toEmail)
                .build()
                .toUriString();

        SimpleMailMessage message = buildMessage(toEmail, link);

        // Never log the link/token: it is a bearer credential for the reset flow.
        log.info("Sending password reset email");
        try {
            mailSender.send(message);
        } catch (MailException mailException) {
            // Swallowed deliberately: a delivery failure must not propagate to the
            // caller. It is only ever attempted for a registered email, so letting
            // it surface (e.g. as a 500) would tell an attacker the email exists —
            // see the contract on MailService.sendPasswordResetEmail.
            log.error("Failed to send password reset email", mailException);
        }
    }

    private @NonNull SimpleMailMessage buildMessage(String toEmail, String link) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject("Reset your recettes password");
        message.setText("""
                We received a request to reset your password.
                
                Click the link below to choose a new one. This link expires soon and can only be used once.
                
                %s
                
                If you didn't request this, you can safely ignore this email.
                """.formatted(link));
        return message;
    }
}
