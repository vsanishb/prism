# Identity

Staff accounts, sign-in, sessions, roles, and permissions. Package: `dev.prism.core.identity`. Design: [plan](../plans/authentication-authorization.md).

## Public API

`Role` and `Permission`. Everything else is in `identity.internal`, and ArchUnit prevents other modules from using it. Other modules check permissions with `@PreAuthorize("hasAuthority('...')")`.

## Key types

| Type | Purpose |
|---|---|
| `SignInService` | Throttling, authentication, generic failures |
| `LoginThrottle` | Postgres-backed backoff, keyed by HMAC of account and address |
| `AccountTokenService` | Invitation and reset links: hash-only, single-use, expiring |
| `UserAdministrationService` | Roles and status, with last-administrator protection |
| `SessionRevoker` | Ends a user's sessions on role, status, or password change |
| `PasswordPolicy` | Length and blocklist rules |
| `FirstAdministratorBootstrap` | Writes a one-time invitation to a file on an empty system |
| `SyntheticUserSeeder` | Local accounts, in the `seed` profile only |

## Endpoints

| Route | Access |
|---|---|
| `GET /api/v1/auth/csrf`, `POST /api/v1/auth/sign-in`, `POST /api/v1/auth/tokens/{inspect,redeem}` | Public |
| `POST /api/v1/auth/sign-out`, `GET /api/v1/auth/me` | Signed in |
| `/api/v1/users/**`, `/api/v1/invitations/**` | `USERS_MANAGE` |

## Data

`core.users`, `core.user_roles`, `core.account_tokens`, `core.login_throttle`, and Spring Session tables.

## Behavior to know

- `PrismUserDetails.getUsername()` returns the user ID, not the email, because sessions are indexed by it.
- Inactive accounts are refused after the password check, so that response time gives nothing away.
- The session cookie is configured in `SecurityConfig` ([D-047](../decisions.md)). `server.servlet.session.cookie.*` has no effect.
- Links carry tokens in the URL fragment, which browsers do not send to servers.
- There is no email service: administrators copy invitation and reset links by hand.

## Not yet built

MFA (M9) and self-service password change.

## Testing

`SignInFlowTest`, `InvitationFlowTest`, `UserAdministrationTest`, `IdentityUnitTest`, and `AccessControlTest`, which fails automatically if a new route lacks protection.
