package ilenreste.unpeu.recettesback.services.users;

public interface MailService {

    /**
     * Sends the user a link letting them choose a new password.
     * <p>
     * Best-effort: implementations must not propagate delivery failures (e.g.
     * an SMTP outage) to the caller. {@code PasswordResetService.requestReset}
     * responds identically whether or not an email is registered, and doing so
     * only holds if a transient mail failure is indistinguishable from success
     * to the caller — otherwise it becomes an oracle for which addresses have
     * accounts, since this method is only ever invoked for a registered email.
     *
     * @param toEmail    the recipient's address
     * @param resetToken the raw (unhashed) one-time reset token
     */
    void sendPasswordResetEmail(String toEmail, String resetToken);
}
