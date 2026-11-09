# Prism Documentation Guide

This documentation is the durable memory of the project. Each file has one purpose, so a fact lives in one place and other files link to it.

## Find what you need

| Question | File |
|---|---|
| What is Prism, and what are its rules? | [project-context.md](project-context.md) |
| How is the system structured? | [architecture.md](architecture.md) |
| How does a process run end to end? | [system-flows.md](system-flows.md) |
| What are the milestones? | [roadmap.md](roadmap.md) |
| What actually exists today? | [progress.md](progress.md) |
| Why was something decided, and which versions are pinned? | [decisions.md](decisions.md) |
| How is the system secured? | [security.md](security.md) |
| How is it tested? | [testing.md](testing.md) |
| How accurate is each component? | [evaluations/](evaluations/README.md) |
| How do I test a milestone by hand? | [test-guides/](test-guides/README.md) |
| How will a capability be built? | [plans/](plans/README.md) |
| How does an implemented module work? | [modules/](modules/README.md) |
| What are the coding standards? | [../CLAUDE.md](../CLAUDE.md) |

## Recovering context

Read in this order: `project-context.md`, then `progress.md`, then the current milestone in `roadmap.md`, then the plan for the work at hand.

**When sources disagree:** code is the truth about what exists. `progress.md` should match it; if it does not, fix it. `decisions.md` is the truth about why. Plans describe intent, not reality.

## Plans and modules

- **Plans** describe intended design and are written before implementation.
- **Module docs** describe what was built. Create one when a module is implemented, and update its plan if the design changed.

## What to update after a change

| Change | Update |
|---|---|
| Any meaningful work | `progress.md` |
| Milestone checklist item finished | `roadmap.md` |
| Module behavior | Its doc in `modules/` |
| Components, boundaries, or data ownership | `architecture.md` |
| A process flow | `system-flows.md` |
| Significant choice or version change | `decisions.md` (add an entry; do not rewrite old ones) |
| Security behavior | `security.md` |
| Test approach or thresholds | `testing.md` |
| Product direction | `project-context.md` |

## Writing rules

- Be concise and factual. Prefer tables and short lists.
- Do not repeat what another file owns: link to it.
- Describe plans as plans. State what is unverified.
- Use Mermaid for diagrams.
