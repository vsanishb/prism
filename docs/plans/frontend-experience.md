# Frontend Experience

## Purpose
A polished, premium enterprise interface that makes dense evidence easy to understand and exposes every capability for real testing.

## Scope
**In:** design system, application shell, all feature screens, and states for long-running AI work.
**Out:** dark mode (strictly light by requirement), native mobile apps.

## Design considerations

**Direction:** calm, precise, and spacious. Hierarchy comes from type, spacing, and alignment instead of boxes and color.

| Element | Direction |
|---|---|
| Color | Neutral base with one restrained accent. Semantic colors only for status. No decorative gradients |
| Typography | One refined sans-serif with clear scale; tabular numerals for financial data |
| Layout | 8-pixel spacing grid, generous margins, clear content width |
| Surfaces | White and near-white, with subtle borders. Shadows only for overlays |
| Motion | Short and purposeful: confirms state changes. Respects reduced-motion settings |
| Data | Aligned tables, right-aligned money, quiet row dividers, no heavy grids |

**Principles**
- Show one primary action per view.
- Evidence sits next to what it supports: claims and source documents are shown side by side.
- Use consistent visual language for field state and stage status.
- Confidence is shown with words and evidence, not false-precision percentages.
- Never present AI output as a decision. Label it clearly as an AI assessment, distinct from human actions.
- Risk language stays neutral: "indicators" and "needs attention", never "fraudulent".
- Desktop is the primary target. Tablets are fully supported, and phones get a usable, simplified layout.

**Required states:** every screen handles loading (skeletons), empty, error, and permission-denied. AI stages show Not started, Processing, Completed, Requires review, or Failed, with a clear next step.

## Dependencies
Core API. Stack is in [D-007](../decisions.md).

## Components
| Area | Contents |
|---|---|
| Foundations | Tokens, typography, spacing, icons |
| Primitives | Button, input, select, dialog, tabs, tooltip, toast |
| Data display | Table, description list, status badge, timeline, money and date formatters |
| Prism-specific | Stage tracker, document viewer with highlights, fact row, citation chip, risk indicator card |
| Shell | Role-aware navigation, header, breadcrumbs |
| API layer | Typed client generated from OpenAPI; shared error handling |

Feature code lives in `web/src/features/<feature>`, and shared UI in `web/src/components`. Routes stay thin.

## Testing
Component tests for primitives; Playwright for each milestone flow; accessibility to WCAG 2.1 AA; visual review at desktop and tablet widths.

## Completion criteria
Per milestone: the flow works against real services, all states exist, and the screens meet this design standard. Screens are never placeholders.
