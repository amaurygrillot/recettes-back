# Password reset ("forgot password")

## What it does

A user who forgot their password can:

1. `POST /auth/reinit-password` with `{ "email": "..." }`. If that email belongs to an account, we email a one-time link
   containing a reset token. The response is identical (`202 Accepted`) whether or not the email is registered.
2. `PUT /users/reinit-password` with `{ "email", "token", "newPassword" }`. If the token is valid for that email, the
   password is updated.

Both routes are public (no JWT) — see `SecurityFilterConfig` and
`JwtAuthenticationFilter.PUBLIC_ENDPOINTS`.

## Why the token isn't a JWT

Every other token in this app is a signed JWT, but the reset token intentionally isn't:

- It needs to be **single-use** and **revocable on demand** (e.g. invalidated the moment a new one is requested). A
  signed JWT is only invalidated by its expiry — there's no revocation list here (see the "Authentication flow" note in
  the root `CLAUDE.md`) — so it's the wrong shape for something that must die after one use.
- It carries no claims worth signing; it's just a capability to reset one specific account's password once.

Instead, `PasswordResetTokenService` generates 256 bits of `SecureRandom`, and persists only its SHA-256 hash in
`password_reset_tokens`. The raw token exists in plaintext only in the outgoing email and the requester's memory — never
in a log line, and never at rest. This mirrors how most frameworks (Django, Devise, Rails) implement password reset
tokens.

The row is deleted the moment it's looked up to be consumed, regardless of whether it turns out to be expired. That
makes "single use" a structural property instead of a flag to remember to check, and it means a replayed token fails
exactly the same way as an unknown one.

Issuing a new token deletes any previous token for that user first, so at most one reset link is ever live per account.

## Why `POST /auth/reinit-password` always returns 202

The root `CLAUDE.md` asks for status codes that reflect the real outcome rather than a generic catch-all. This endpoint
is a deliberate, documented exception:
if it replied differently for a registered vs. unregistered email, it would become an oracle for enumerating every
account's email address. Returning the same `202 Accepted` either way — and doing nothing observable when the email is
unknown (see `PasswordResetService.requestReset`) — trades a bit of literal accuracy for closing that side channel.

`PUT /users/reinit-password` follows the same principle: an unknown email, an unknown token, an expired token, and a
token issued to a different account all map to the same `400 Bad Request`, so a caller can't distinguish "wrong
password" from "wrong email" from "expired link".

## Configuration

| Property                                  | Meaning                                                                       | Default                                |
|-------------------------------------------|-------------------------------------------------------------------------------|----------------------------------------|
| `app.password-reset.token-expiry-minutes` | How long a token stays valid                                                  | `15`                                   |
| `app.password-reset.frontend-reset-url`   | Frontend page the emailed link points to (`?token=...&email=...` is appended) | `http://localhost:3000/reset-password` |
| `app.mail.from`                           | `From:` address on the reset email                                            | `no-reply@recettes.local`              |
| `spring.mail.*`                           | Standard Spring Boot SMTP settings                                            | placeholder, needs real credentials    |

## Prerequisite: unique emails

Resolving an account by email only makes sense if emails are unique.
`UserEntity.email` gained a `unique = true` constraint as part of this feature (previously only `username` was unique).
If any existing rows share an email, the `ddl-auto=update` schema update will fail until that's cleaned up.

## Known limitations / accepted risk

- **No rate limiting** on `POST /auth/reinit-password` yet. Nothing stops someone from spamming an inbox with reset
  emails, or hammering
  `PUT /users/reinit-password` to brute-force a 256-bit token (infeasible by brute force alone, but still worth
  throttling defense-in-depth). Left out of this change to keep scope focused; worth adding (e.g. per-IP/per-email
  throttling) before this is exposed beyond friends and family.
- **Minor timing side-channel** on `requestReset`: the known-email path does a DB write and sends an email, the
  unknown-email path does neither, so response time can leak which case occurred. Accepted for now given the target
  audience; constant-time padding would be the fix if that changes.
- **Resetting the password doesn't revoke existing JWTs.** Since JWTs are stateless and self-contained (see root
  `CLAUDE.md`), any token issued before the reset stays valid until it expires (currently 1 hour, see
  `AuthenticationController.login`). Acceptable at today's scale; would need a revocation/deny-list to close.
