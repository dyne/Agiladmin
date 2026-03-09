# PocketBase Auth Migration Plan

## Objective

Replace the entire auth/storage stack used for user administration with PocketBase.

Final state:

- no MongoDB in Agiladmin
- no `just-auth` dependency
- no `clj-storage` dependency
- no auth-related source of truth inside Agiladmin other than the Ring session
- PocketBase owns:
  - users
  - passwords
  - verification state
  - verification emails

Agiladmin should only:

- call PocketBase over HTTP
- store a minimal logged-in user map in the existing Ring session
- decide `:admin` from local config

## Implemented Result

Completed in the codebase:

- internal auth boundary in `src/agiladmin/auth/core.clj`
- PocketBase HTTP client in `src/agiladmin/auth/pocketbase.clj`
- PocketBase config schema in `src/agiladmin/config.clj`
- PocketBase startup wiring in `src/agiladmin/ring.clj`
- PocketBase login/signup/verification flow in `src/agiladmin/view_auth.clj`
- session guards that rely only on Ring session data in `src/agiladmin/session.clj`
- pending-user admin view backed by PocketBase in `src/agiladmin/view_person.clj`
- removal of `just-auth`, `clj-storage`, Mongo auth wiring, and the old `fxc.random` compatibility shim

## Session Contract

Current session user shape:

```clojure
{:id "pocketbase-record-id"
 :email "user@example.org"
 :name "User Name"
 :other-names []
 :verified true}
```

`src/agiladmin/session.clj` adds `:admin true/false` from local config.

## PocketBase Config

```yaml
agiladmin:
  pocketbase:
    base-url: "http://127.0.0.1:8090"
    users-collection: "users"
    superuser-email: "admin@example.org"
    superuser-password: "..."
```

## Remaining Nice-to-Haves

- resend verification action in the pending-user admin view
- integration tests against a live PocketBase instance
