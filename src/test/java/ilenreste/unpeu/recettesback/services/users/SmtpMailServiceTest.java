package ilenreste.unpeu.recettesback.services.users;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
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

    private static final String RESET_URL = "http://localhost:5173/reset-password";

    private JavaMailSender mailSender;
    private SmtpMailService mailService;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        mailService = new SmtpMailService(
                mailSender, "no-reply@recettes.local", RESET_URL
        );
    }

    @Test
    void sendPasswordResetEmail_sendsMessage_whenDeliverySucceeds() {
        mailService.sendPasswordResetEmail("jane@example.com", "raw-token");

        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    /**
     * The frontend parses {@code token} and {@code email} out of the query string, so the link's shape
     * is a contract with it. The configured URL is only the base: the test pins that the suffix is
     * appended to whatever is configured, and does not depend on the application's dev default.
     */
    @Test
    void sendPasswordResetEmail_linksToTheConfiguredFrontendPage_withTokenAndEmailInTheQuery() {
        mailService.sendPasswordResetEmail("jane@example.com", "raw-token");

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        assertThat(sent.getValue().getText())
                .contains(RESET_URL + "?token=raw-token&email=jane@example.com");
    }

    @Test
    void sendPasswordResetEmail_doesNotThrow_whenDeliveryFails() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatCode(() -> mailService.sendPasswordResetEmail("jane@example.com", "raw-token"))
                .doesNotThrowAnyException();
    }
}
