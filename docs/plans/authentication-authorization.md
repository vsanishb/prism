# Authentication and Authorization

## Purpose
Establish who a user is and what they may do, enforced on the server. Controls are defined in [security.md](../security.md); this plan covers how they are built.

## Scope
**In:** invitations, password sign-in and reset, sessions, roles and permissions, object-level access, separation of duties, user administration, service JWT.
**Out:** SSO, public registration. MFA arrives in M9.

## Design considerations
- Use Spring Security's filter chain rather than custom authentication code.
- Check authorization in the service layer with method security, so that every entry point is covered.
- Express permissions as an enum mapped to roles in code, so that changes are reviewed and tested.
- Keep object-level checks in dedicated policy classes (for example `ClaimAccessPolicy`).
- Do not reveal whether an account exists through sign-in, reset, or invitation responses.
- Role changes revoke active sessions.

## Dependencies
Flyway and schemas; audit module; frontend shell.

## Components
| Component | Responsibility |
|---|---|
| `identity` module | Users, roles, invitations, password handling |
| Session configuration | Spring Session JDBC, cookies, timeouts |
| `LoginThrottle` | Postgres-backed backoff per account and IP |
| Permission model | Permission enum, role mapping, method security |
| `aiclient` signer | Signs service JWTs in core |
| `api/auth` in AI | Verifies service JWTs |
| Web screens | Sign-in, accept invitation, reset, user administration |

## Testing
Full list in [testing.md](../testing.md#required-security-tests). Each endpoint needs allow and deny cases. A generated test fails if any route lacks a declared access rule.

## Completion criteria
- Users can only be created by invitation.
- All sensitive routes deny by default.
- Throttling, session rotation, and revocation work.
- AI rejects all calls lacking a valid core token.
- All authentication events are audited.
