# Changing a password or an email

Two different flows set a password, and they prove identity in two different ways. Keeping them separate is the whole
design, so this is written down rather than left to be re-derived from the code.

| Flow | Endpoint | What proves you may do this |
|---|---|---|
| I know my password and want a new one | `PUT /users/update` | the **current password**, in the same request |
| I have forgotten my password | `POST /auth/reinit-password` → `PUT /users/reinit-password` | a **one-time token** mailed to the address on the account |

## Why `PUT /users/update` asks for the current password

It did not, and that was the hole. A bearer token proves the caller held a valid login at some point in the last hour.
It does not prove they are the account holder, and for most fields that distinction does not matter much — a wrong
`firstname` is an annoyance the owner can undo.

Two fields are different, because a change to either outlives the token that authorised it:

- **`password`** — the new password keeps working after the stolen token expires. The owner is locked out.
- **`email`** — `POST /auth/reinit-password` mails the address stored on the account. Replace it and you have taken away
  the only route back in.

Together they turn one leaked token (XSS, a logged `Authorization` header, a shared laptop) into an account the owner
cannot recover: change the password, change the email, and the reset link now arrives in somebody else's inbox.

Re-entering the current password costs a legitimate user one field. It costs an attacker the one thing a stolen token
does not carry.

### What is *not* gated, and why

Only `password` and `email`. `username`, `firstname` and `lastname` go through untouched. Demanding a password to fix a
typo in a surname would train people to type their password into whatever asks for it, which is a worse security
posture than the one it would be protecting.

### The refusal is a 403, and missing is the same as wrong

`ForbiddenOperationException` → **403**, not 400. The payload is well formed; the caller simply has not proved they may
use it, which is exactly what 403 means in this API (see [api-error-handling.md](api-error-handling.md)).

A **missing** `currentPassword` and a **wrong** one answer identically, with the same message. Distinguishing them
would make the endpoint a password oracle for whoever already holds the token — they could sit there testing guesses
and read the difference off the status.

## Why the reset flow does not go through `updateUser`

`PasswordResetService.resetPassword` calls `UserService.applyNewPassword(user, newPassword)`, **not**
`UserService.updateUser`. That is deliberate and it is the thing most likely to be "simplified" back.

Somebody who has forgotten their password cannot supply it. If the reset flow went through `updateUser`, one of two
things would have to be true:

1. the reset would demand a current password — locking out precisely the people the feature exists for; or
2. `updateUser` would have to accept "no current password" as valid — which removes the guard for *both* callers, and
   the authenticated path is the one that needs it.

So they are two methods, not one method with a `skipCurrentPasswordCheck` flag. They answer *"why may this caller set a
password?"* differently, and a flag is one boolean away from answering it wrongly. `applyNewPassword` is also narrow on
purpose: it takes a password and nothing else, so a reset cannot reach the username or the email on the way past.

The authorisation for `applyNewPassword` happens one line above the call, in `resetPassword`, which consumes the
one-time token in the same transaction — so a failure applying the password puts the token back. See
[password-reset.md](password-reset.md).

## `@Email`, not a length check

`UpdateUserRequest.email` was `@Size(min = 3, max = 50)`, so `"abc"` was an acceptable email address. It is
`@Email @Size(max = 254)` now (254 being the RFC 5321 limit, which fits the existing `varchar(255)` column, so
`ddl-auto=update` has nothing to do).

This is not cosmetic. A stored value that is not an address breaks `POST /auth/reinit-password` silently: the reset mail
goes nowhere, and the failure surfaces as *"no mail ever arrived"* — nowhere near the request that caused it, and on the
one flow whose error handling is deliberately uninformative, so nothing in the response can hint at the cause.

## Uniqueness is pre-checked on update too

`createUser` already checked `existsByUsername` before inserting. `updateUser` checked nothing, even though
`UserEntity` marks both `username` and `email` `unique = true`. The constraint therefore fired at **commit** — after
`log.info("Updated user {}", id)` had already recorded the update as a success — and surfaced as a
`DataIntegrityViolationException` that names neither the field nor the colliding row. Worse, the handler logged it as
*"A unique constraint lost a create race"*, pointing an operator at registrations for a conflict raised by a PUT.

Both columns are now pre-checked on both paths, skipping the check when the value is unchanged so that re-sending your
own username is not a conflict with yourself. `createUser` also checks `email`, which it never did.

The constraint is still what *guarantees* the invariant — the pre-check is for the message and the log line. A genuinely
concurrent write still loses the race and still arrives as a 409; the handler's log now says "lost a race" rather than
naming a flow it may not have come from.

## Logging

A credential change emits its own `warn` line naming the account and which of the two fields changed — never the
values. `log.info("Updated user {}", id)` could not distinguish "fixed a typo in my surname" from "took the account
over", and the second is the line an owner reporting a hijacked account needs found.
