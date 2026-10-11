package ilenreste.unpeu.recettesback.services.users;

import ilenreste.unpeu.recettesback.entities.users.RoleEntity;
import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.entities.users.UserRolesEntity;
import ilenreste.unpeu.recettesback.exceptions.ForbiddenOperationException;
import ilenreste.unpeu.recettesback.exceptions.ResourceConflictException;
import ilenreste.unpeu.recettesback.exceptions.ResourceNotFoundException;
import ilenreste.unpeu.recettesback.mappers.users.UserMapper;
import ilenreste.unpeu.recettesback.models.users.requests.CreateUserRequest;
import ilenreste.unpeu.recettesback.models.users.requests.UpdateUserRequest;
import ilenreste.unpeu.recettesback.models.users.responses.UserResponse;
import ilenreste.unpeu.recettesback.repositories.users.RolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UserRolesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
import lombok.extern.log4j.Log4j2;
import org.jspecify.annotations.NullMarked;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Accounts.
 * <p>
 * Both write methods return the stored account rather than {@code void}. That is
 * not cosmetic: a {@code void} create forces the controller above to answer
 * {@code 201} on the strength of "nothing threw", with no id to put in a
 * {@code Location} header and no way for a client to see what was actually
 * persisted. Returning the mapped row makes the success path carry its own
 * evidence, and every failure path below throws.
 */
@Log4j2
@Service
@NullMarked
public class UserService {

    /** Granted to every new account. Must exist; see {@link #requireRole}. */
    private static final String DEFAULT_ROLE = "USER";

    private final UsersRepository usersRepository;
    private final RolesRepository rolesRepository;
    private final UserRolesRepository userRolesRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper mapper;

    public UserService(
            UsersRepository usersRepository, RolesRepository rolesRepository, UserRolesRepository userRolesRepository,
            PasswordEncoder passwordEncoder, UserMapper mapper
    ) {
        this.usersRepository = usersRepository;
        this.rolesRepository = rolesRepository;
        this.userRolesRepository = userRolesRepository;
        this.passwordEncoder = passwordEncoder;
        this.mapper = mapper;
    }

    /**
     * Registers an account and grants it {@code USER}, in one transaction — an
     * account without its role row can sign in and then fail every
     * {@code hasRole("USER")} rule, which looks like a permissions bug rather
     * than a half-finished insert.
     *
     * @return the stored account, including the generated id and the role it was granted
     * @throws ResourceConflictException if the username is taken, as a 409 rather
     *                                   than letting the unique constraint surface
     *                                   as an opaque failure
     */
    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        if (usersRepository.existsByUsername(request.username())) {
            log.info("Refusing to create an account: the username is already taken");
            throw new ResourceConflictException("Username already exists");
        }
        // Checked for the same reason as the username, and previously missed: email is
        // unique too, so registering with a taken address otherwise surfaced as an opaque
        // DataIntegrityViolationException instead of a 409 naming the field.
        if (usersRepository.existsByEmail(request.email())) {
            log.info("Refusing to create an account: the email is already registered");
            throw new ResourceConflictException("Email already exists");
        }

        // Looked up before the insert, so a missing role row fails with a message naming the cause
        // rather than as a NOT NULL violation on user_roles.role_id two statements later.
        RoleEntity role = requireRole(DEFAULT_ROLE);

        UserEntity user = new UserEntity();
        user.setUsername(request.username());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setEnabled(true);
        user.setEmail(request.email());
        user.setFirstname(request.firstname());
        user.setLastname(request.lastname());

        UserEntity saved = usersRepository.save(user);

        // Written through UserRolesEntity, not user.getRoles().add(...): user_roles is mapped both
        // as the @ManyToMany join table and as an entity with its own id column, so a @ManyToMany
        // insert leaves that NOT NULL id null and fails. That includes adding to the managed
        // saved.getRoles() "just for the response": Hibernate flushes the dirty collection at
        // commit, which is why the role is handed to the mapper instead.
        UserRolesEntity link = new UserRolesEntity();
        link.setUser(saved);
        link.setRole(role);
        userRolesRepository.save(link);

        // Debug, not info: the transaction has not committed yet, so "created" is not true until
        // the service returns. The controller logs that.
        log.debug("Linked user {} to role {}", saved.getId(), DEFAULT_ROLE);
        return mapper.toResponse(saved, List.of(role));
    }

    /**
     * Applies a partial update to the calling account.
     *
     * <h2>Changing the password or the email needs the current password</h2>
     * A bearer token proves the caller held a valid login at some point in the
     * last hour. It does not prove they are the account holder, and these two
     * fields are the ones where that difference is permanent: a password change
     * outlives the token that authorised it, and an email change takes the
     * account's only route back — {@code POST /auth/reinit-password} mails the
     * stored address. Together they turn one leaked token into an account the
     * owner cannot recover. Re-entering the current password costs a legitimate
     * user one field and costs an attacker the one thing a stolen token does not
     * carry.
     * <p>
     * The other fields are deliberately <em>not</em> gated. A wrong firstname is
     * an annoyance the owner can undo; demanding a password to fix a typo would
     * train people to type it into whatever asks.
     * <p>
     * <strong>This is not the forgotten-password path.</strong> Somebody who has
     * forgotten their password cannot present it, so requiring it here would
     * lock out exactly the people who need to get back in. That flow is
     * {@code PUT /users/reinit-password} → {@code PasswordResetService} →
     * {@link #applyNewPassword}, where the emailed one-time token is the proof
     * instead. The two paths are separate methods rather than one method with a
     * flag, because they answer "why may this caller set a password?"
     * differently and a flag is one boolean away from answering it wrongly.
     *
     * @return the account as stored after the update
     * @throws ResourceNotFoundException   if the id names no account — a 404 rather
     *                                     than a silent no-op, which would tell a
     *                                     caller their change was applied when
     *                                     there was nothing to apply it to
     * @throws ForbiddenOperationException if a password or email change arrives
     *                                     without the current password, or with the
     *                                     wrong one. 403 and not 400: the payload is
     *                                     well formed, the caller just has not proved
     *                                     they may use it. Missing and wrong answer
     *                                     identically, so the response cannot be used
     *                                     to test passwords
     * @throws ResourceConflictException   if the new username or email is already
     *                                     taken by another account
     */
    @Transactional
    public UserResponse updateUser(String userId, UpdateUserRequest request) {
        UserEntity user = usersRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("user", userId));

        boolean changesCredentials = request.password().isPresent() || request.email().isPresent();
        if (changesCredentials) {
            requireCurrentPassword(user, request.currentPassword());
        }
        requireUnusedUsername(user, request.username());
        requireUnusedEmail(user, request.email());

        request.email().ifPresent(user::setEmail);
        request.firstname().ifPresent(user::setFirstname);
        request.lastname().ifPresent(user::setLastname);
        request.username().ifPresent(user::setUsername);
        request.password().ifPresent(password -> user.setPassword(passwordEncoder.encode(password)));

        UserEntity saved = usersRepository.save(user);
        if (changesCredentials) {
            // Logged explicitly, and at warn: "the password or the email changed" is the line an
            // owner reporting a hijacked account needs found, and "Updated user {id}" does not say
            // it. Neither value is logged - the point is that it changed, not what to.
            log.warn("Credentials changed for user {} (password: {}, email: {})", saved.getId(),
                    request.password().isPresent(), request.email().isPresent());
        }
        log.info("Updated user {}", saved.getId());
        return mapper.toResponse(saved);
    }

    /**
     * Sets a new password for an account whose holder has proved themselves some
     * other way — today, by presenting the one-time token mailed to the address
     * on the account.
     * <p>
     * Narrow on purpose. It takes a password and nothing else, so the reset flow
     * cannot reach the username or the email on the way past, and it asks for no
     * current password because the whole premise is that the caller does not
     * know it. The authorisation for this call happens in
     * {@code PasswordResetService.resetPassword}, which consumes the token in
     * the same transaction — so a failure here puts the token back.
     */
    @Transactional
    public UserResponse applyNewPassword(UserEntity user, String newPassword) {
        user.setPassword(passwordEncoder.encode(newPassword));
        UserEntity saved = usersRepository.save(user);
        log.warn("Password reset applied for user {} via a one-time token", saved.getId());
        return mapper.toResponse(saved);
    }

    /**
     * Both the absent and the wrong case throw the same exception with the same
     * message. Distinguishing them would turn the endpoint into a password
     * oracle for whoever already holds the token.
     */
    private void requireCurrentPassword(UserEntity user, Optional<String> currentPassword) {
        boolean proved = currentPassword
                .filter(supplied -> passwordEncoder.matches(supplied, user.getPassword()))
                .isPresent();
        if (!proved) {
            log.warn("Refusing a credential change for user {}: the current password was not proved",
                    user.getId());
            throw new ForbiddenOperationException(
                    "Changing your password or email requires your current password.");
        }
    }

    /**
     * Checked here rather than left to the unique constraint, for the same
     * reason {@link #createUser} checks it: the constraint fires at commit, by
     * which point the success has already been logged, and it surfaces as a
     * {@code DataIntegrityViolationException} that names neither the field nor
     * the colliding row.
     * <p>
     * Skipped when the value is unchanged, so re-sending your own username is
     * not a conflict with yourself.
     */
    private void requireUnusedUsername(UserEntity user, Optional<String> username) {
        username.filter(requested -> !requested.equals(user.getUsername()))
                .filter(usersRepository::existsByUsername)
                .ifPresent(taken -> {
                    log.info("Refusing to update user {}: the username is already taken", user.getId());
                    throw new ResourceConflictException("Username already exists");
                });
    }

    private void requireUnusedEmail(UserEntity user, Optional<String> email) {
        email.filter(requested -> !requested.equals(user.getEmail()))
                .filter(usersRepository::existsByEmail)
                .ifPresent(taken -> {
                    log.info("Refusing to update user {}: the email is already registered", user.getId());
                    throw new ResourceConflictException("Email already exists");
                });
    }

    /**
     * The {@code roles} table is seeded by {@code ReferenceDataSeeder} at startup,
     * so a missing row here means the application is misconfigured rather than
     * that the request was wrong — hence a 500, logged with the role name, and not
     * a 400 blaming the caller for something they cannot fix.
     */
    private RoleEntity requireRole(String roleName) {
        RoleEntity role = rolesRepository.findByNameEqualsIgnoreCase(roleName);
        if (role == null) {
            log.error("The '{}' role is missing from the roles table; no account can be created "
                    + "until it is seeded", roleName);
            throw new IllegalStateException("The '%s' role is missing.".formatted(roleName));
        }
        return role;
    }
}
