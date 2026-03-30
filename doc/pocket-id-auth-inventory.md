# Pocket ID Auth Inventory

This note classifies the current auth use-cases before adding Pocket ID.

## Provider-neutral behavior to keep

- `agiladmin.auth.core/healthy?`
- Session user normalization in `agiladmin.session/normalize-role`
- Ring session storage of the authenticated user
- `view-auth/logout-get`

## PocketBase-specific behavior

- `agiladmin.auth.core/sign-in` with email/password
- `agiladmin.auth.core/sign-up`
- `agiladmin.auth.core/confirm-verification`
- `agiladmin.auth.core/request-verification`
- `agiladmin.auth.core/list-pending-users`
- `view-auth/signup-post`
- `view-auth/activate`

## Behavior that must be replaced for Pocket ID

- `view-auth/login-post` cannot stay password-centric
- `web/login-form` cannot stay email/password-only
- `ring/init` cannot infer the backend from `:agiladmin :pocketbase`
- Pending-user admin behavior must become optional per backend

## Target direction

- Keep the existing auth boundary as a map-based port.
- Add redirect-based login operations for Pocket ID.
- Keep provider-specific capabilities optional instead of mandatory.
- Preserve the Ring session user shape consumed by the rest of the app.
