# Pocket ID Migration Notes

This is the shortest safe path from PocketBase auth to Pocket ID auth.

## What does not migrate automatically

- users
- passwords
- passkeys
- PocketBase `role` field values
- verification or pending-user state

Pocket ID becomes the identity source of truth. Agiladmin only keeps the logged-in user in the Ring session.

## Role migration

- Create one Pocket ID group for Agiladmin admins.
- Create one Pocket ID group for Agiladmin managers.
- Map them into `agiladmin.auth.pocket-id.admin-group` and `agiladmin.auth.pocket-id.manager-group`.
- Users without either group authenticate successfully but keep a nil Agiladmin role.

## Onboarding change

- PocketBase mode: Agiladmin can show local signup and activation flows.
- Pocket ID mode: onboarding happens in Pocket ID, including passkey enrollment.

## Cutover

1. Create the Pocket ID OIDC client.
2. Set the callback URI to `/auth/pocket-id/callback`.
3. Create and assign the Agiladmin groups in Pocket ID.
4. Switch `agiladmin.auth.backend` to `pocket-id`.
5. Restart Agiladmin.

Existing Agiladmin sessions should be treated as stale during cutover. Users should log in again.

## Rollback

1. Restore the PocketBase config block.
2. Switch `agiladmin.auth.backend` back to `pocketbase`.
3. Restart Agiladmin.

No Agiladmin data migration is required for rollback because auth state is external to the app.
