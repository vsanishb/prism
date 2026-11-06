# M1 Test Guide: Secure Platform

How to try Milestone 1 through the UI. All accounts are synthetic.

## Start Prism

First-time setup, from the repository root:

```bash
python -m pip install uv
```

```bash
python -m uv sync --project services/ai
```

```bash
python -m uv run --project services/ai python scripts/prism.py setup
```

```bash
python -m uv run --project services/ai python scripts/prism.py migrate
```

```bash
python -m uv run --project services/ai python scripts/prism.py seed
```

```bash
npm install --prefix web
```

Each time you work, run these in separate terminals:

```bash
python -m uv run --project services/ai python scripts/prism.py db-start
```

```bash
cd services/ai && python -m uv run python -m prism_ai.serve
```

```bash
cd services/core && ./mvnw spring-boot:run
```

```bash
npm run dev --prefix web
```

Open <http://localhost:3000>.

## Synthetic data

Seeding creates one account per role, such as `admin@synthetic.prism.test`. Passwords are random and listed in `.data/synthetic-users.md`, which is gitignored. Running the seed command again generates new passwords.

To return to a clean state at any time, which also removes the audit history:

```bash
python -m uv run --project services/ai python scripts/prism.py db-reset
```

| Account | Sees |
|---|---|
| `admin@…` | Overview, Users, System status |
| `auditor@…` | Overview, Audit trail |
| Every other role | Overview only, until later milestones add their features |

## Flows to try

### 1. Sign-in

1. Sign in with a wrong password. **Expect:** "Email or password is incorrect."
2. Do the same with an unknown email. **Expect:** the identical message.
3. Fail five times on one account. **Expect:** a "Too many attempts" message, even with the correct password. It clears within a few minutes.
4. Sign in as the administrator. **Expect:** the overview, and "Synthetic data only" in the sidebar.

### 2. Invite a staff member

1. As administrator, open **Users** and choose **Invite**.
2. Submit the empty form. **Expect:** errors beside each field.
3. Fill it in with a `@synthetic.prism.test` email, pick **Reviewer**, and create it. **Expect:** a one-time link, and the person listed under Pending invitations.
4. Open the link in a private window. Try the password `password1234`. **Expect:** it is refused as too common.
5. Finish with a long passphrase, then sign in. **Expect:** only Overview in the sidebar.
6. Visit `/admin/users` directly. **Expect:** "You do not have access to this page."
7. Open the invitation link again. **Expect:** "This link cannot be used."

### 3. Manage access

1. With the new user still signed in, open **Actions** beside their name and change their role to **Auditor**.
2. Reload the user's window. **Expect:** they are signed out, and Audit trail appears after they sign in again.
3. Choose **Reset password** for them. **Expect:** a one-time link. Using it ends their sessions.
4. **Deactivate** them. **Expect:** sign-in fails with the generic message.
5. Try to deactivate your own administrator account. **Expect:** it is refused.

### 4. Audit trail

1. Sign in as the auditor and open **Audit trail**. **Expect:** your earlier actions, including failures and denied access.
2. Filter by outcome, then open an event. **Expect:** details with no passwords or emails of failed attempts.
3. Choose **Verify integrity**. **Expect:** "The audit trail is intact."

### 5. System status

1. As administrator, open **System status**. **Expect:** all four checks passing, including a refused unsigned call.
2. Stop the AI service and choose **Run checks**. **Expect:** failing checks, with no crash.
3. Restart it and run the checks again. **Expect:** all passing.

## Automated checks

With all services running, and after `npm run build --prefix web`:

```bash
npm run e2e --prefix web
```
