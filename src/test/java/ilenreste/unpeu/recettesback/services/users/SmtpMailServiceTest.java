package ilenreste.unpeu.recettesback.services.users;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Regression test: a delivery failure must never propagate out of
 * sendPasswordResetEmail. It's only ever invoked for a registered email
 * (see PasswordResetService.requestReset), so letting a MailException escape
 * would let a caller distinguish a registered email (500, because sending
 * failed) from an unregistered one (always 202) - defeating the whole point
 * of PasswordResetService responding identically either way.
 */
class SmtpMailServiceTest {

    private JavaMailSender mailSender;
    private SmtpMailService mailService;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        mailService = new SmtpMailService(
                mailSender, "no-reply@recettes.local", "http://localhost:3000/reset-password"
        );
    }

    @Test
    void sendPasswordResetEmail_sendsMessage_whenDeliverySucceeds() {
        mailService.sendPasswordResetEmail("jane@example.com", "raw-token");

        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void sendPasswordResetEmail_doesNotThrow_whenDeliveryFails() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatCode(() -> mailService.sendPasswordResetEmail("jane@example.com", "raw-token"))
                .doesNotThrowAnyException();
    }
}
