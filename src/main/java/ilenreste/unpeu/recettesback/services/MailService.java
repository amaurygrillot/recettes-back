package ilenreste.unpeu.recettesback.services;

public interface MailService {

    /**
     * Sends the user a link letting them choose a new password.
     *
     * @param toEmail    the recipient's address
     * @param resetToken the raw (unhashed) one-time reset token
     */
    void sendPasswordResetEmail(String toEmail, String resetToken);
}
