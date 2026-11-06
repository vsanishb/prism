# Web Foundation

The design system and application shell. Code: `web/src`. Design: [plan](../plans/frontend-experience.md).

## Layout

| Path | Contents |
|---|---|
| `app/` | Thin routes. `(auth)` for public pages, `(app)` for the signed-in shell |
| `features/` | Feature components |
| `components/ui/` | Shared primitives |
| `lib/` | API access, labels, formatting, types |
| `proxy.ts` | Security headers, CSP nonce, sign-in redirect |

Design tokens live in `app/globals.css`. Use them, never raw colors.

## Data access

- Server Components use `serverFetch`, which forwards the visitor's cookies to core.
- Client components use `apiRequest`, which adds the CSRF header. Mutations then call `router.refresh()`.
- Browser requests to `/api/*` are rewritten to core, so everything is same-origin.

## Behavior to know

- Hiding navigation is cosmetic. Core enforces all access, and pages handle `403` by showing `AccessDenied`.
- The CSRF cookie is cleared at sign-in and sign-out, and `apiRequest` fetches a new one when needed.
- CSP needs a per-request nonce, so all pages render dynamically.
- Dates display in Indian Standard Time through `lib/format.ts`.

## Deferred from the plan

API types are written by hand in `lib/types.ts`. TanStack Query and form libraries ([D-007](../decisions.md)) are not installed yet: M1 has no need for them, so each is added when a screen requires it.

## Testing

Vitest for utilities, Playwright in `e2e/` against the real stack.
