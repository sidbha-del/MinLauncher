# Kids Launcher — PRD

2026-09-19 · MinLauncher

**Lineage:** Pivoted from "Dopamine Budget Launcher," which was disqualified during the three-panel brainstorm despite a competitive raw weighted score (5.25/10). Its core mechanism — a token/time budget that makes distracting apps progressively more expensive to open — was disqualified for adults because the same person being restricted can void the restriction in four taps (Settings → change default launcher), which Android permits by design for a consenting adult on an unsupervised device. That flaw disappears when the person holding the budget and the person able to change it are different people: a parent and a child. This PRD keeps the legible, kid-facing budget mechanic (which is a reasonable pedagogical device for a child) and relocates enforcement authority to the parent via Android's supervised/managed-device APIs, which *can* legitimately lock Settings and default-app switching behind a PIN the child doesn't have.

## Problem Statement

Parents who want real, enforceable screen-time limits for a child's device are currently served by either (a) general parental-control layers bolted on top of the stock launcher (Google Family Link, Qustodio, Bark, OurPact), which don't rethink the child's home-screen experience itself, or (b) consumer digital-wellbeing apps built for adult self-restriction, which fail for a child exactly as they fail for an adult — the restriction and the ability to remove it sit with the same user, and a child who wants around a limit will often find the bypass faster than a distracted adult would.

What's missing is a child's home screen where the budget is the primary interface — visible, legible, and honest about what's left — paired with enforcement that actually holds because it's backed by device-level supervision, not the child's own willpower.

Cost of not solving: parents either over-restrict (blunt app blocking with no nuance) or under-restrict (limits the child can talk or tap their way around), and either way the burden of enforcement falls on repeated in-person parental intervention.

## Goals

1. Child's home screen shows a legible, per-category time budget (not a black-box block) as the primary interface. Target: child can state, unprompted, how much time is left in a category.
2. Enforcement holds without parent intervention. Target: 0% successful child bypass of Settings/default-launcher-switching during the pilot, verified against supervised-device APIs rather than app-level nudging.
3. Parent can configure and adjust everything remotely. Target: 100% of setup and budget-adjustment tasks completable without physically holding the child's device, reusing the remote-pairing pattern already proven in the Senior Accessibility Launcher (`Launcher/Senior Accessibility Launcher — PRD.md`).
4. Budget depletion is explained plainly to the child, never framed as shame or punishment. Target: qualitative parent/child feedback shows no reported shame response, in contrast to the explicitly-rejected "Social Shame Launcher" concept from the original brainstorm.

## Non-Goals

- No covert or stealth monitoring of content, messages, or browsing history. This product manages visible time/app budgets, not surveillance — scope creep here is both an ethical line and a Play Store policy risk.
- No charity-penalty micro-transactions tied to limit violations. Flagged during the original brainstorm as a Play Store billing-policy violation; not revisited.
- No peer-to-peer token trading between children ("Option D: Peer-to-Peer Economy" from the original concept). Requires a backend and social-safety review disproportionate to v1.
- No support for circumventing school- or enterprise-managed device policies. Out of scope; a different governance lane with its own conflicts.
- No iOS. Android's supervised-device and HOME-role APIs make this possible; iOS's parental-control model does not offer equivalent third-party launcher enforcement.

## User Stories

**Parent**

- As a parent, I want to set a daily time budget per app or category (e.g., 30 min social, unlimited reading/education), so my child has real limits without me manually enforcing them.
- As a parent, I want to configure and adjust budgets remotely, so I don't need to physically take the device to make a change.
- As a parent, I want to receive and approve or deny "more time" requests from my child, so I stay in control without being the sole source of friction.
- As a parent, I want confidence that my child cannot switch away from this launcher or into Settings without my PIN.

**Child (primary device user)**

- As a child, I want to see exactly how much time I have left in each category, so limits feel fair and predictable rather than arbitrary.
- As a child, I want a plain-language explanation when a budget runs out, not a locked screen with no context.
- As a child, I want a simple way to ask my parent for more time when I need it.

## Requirements

### P0 — Supervised Enrollment + Basic Budgets

| Requirement | Acceptance Criteria |
| --- | --- |
| Supervised/managed device enrollment | Device is provisioned as a supervised child device via QR/link setup completed by the parent — no technical steps required from the child, reusing the remote-pairing UX pattern from the Senior Accessibility Launcher |
| Remote budget configuration | Parent sets per-app or per-category daily time budgets from a remote console; changes reflect on the child's device within one sync cycle |
| Legible child-facing budget display | Home screen shows remaining time per category as a visible meter, updated in real time |
| Graceful depletion | When a budget is exhausted, the app icon locks with a plain-language explanation (e.g., "Social time is used up for today — more at 6pm") rather than a generic system block |
| Settings/launcher-switching lockout | Child cannot reach system Settings or change the default launcher without the parent's PIN, enforced via Android supervised/Device Owner APIs — not app-level nudging alone |

### P1 — Legible Token Economy + Requests

| Requirement | Acceptance Criteria |
| --- | --- |
| Transparent variable-cost model | Repeated opens of a category within a period cost progressively more budget, with the running cost always visible to the child before they confirm |
| "Request more time" flow | Child can send a one-tap request to the parent; parent approves or denies remotely in one tap |
| Weekly parent summary | Parent receives a plain-language weekly summary of usage and requests, not raw logs |

### P2 — Future, contingent on P0/P1 validating

- Multi-child household view, converging toward the "Family Command Center" concept from the same brainstorm rather than remaining a separate product.
- Positive-reinforcement bonus budget for completed chores/reading, designed carefully to avoid the manipulative-gamification criticisms leveled at the original Dopamine Budget concept.

## Success Metrics

**Leading**

- % of families completing supervised enrollment without in-person technical support.
- Child bypass-attempt success rate during pilot — target 0%, verified against device-owner-level enforcement, in direct contrast to the adult version's fundamental, unfixable bypass problem.
- Parent-reported perceived control, measured against their prior tool (Family Link or none).

**Lagging**

- Sustained use at 4+ weeks without the parent reverting to Family Link or another tool.
- Parent-reported reduction in conflict over screen time.

**Riskiest assumption:** that parents will complete a heavier supervised-device enrollment flow when a free alternative (Google Family Link) already exists — the legible child-facing budget UI and the remote request/approval flow have to be a clearly better experience, not just a different one, to justify the extra setup step.

**Cheapest test:** before building any device-owner provisioning (the most technically expensive part of this product), test a clickable prototype of the child-facing budget screen and the parent remote console with 10-15 parents of children aged 8-14. Measure willingness to complete supervised enrollment and stated willingness to pay — not just reaction to the concept.

## Open Questions

- Build vs. integrate: should enforcement be built on Google Family Link's existing supervision APIs (lower engineering cost, faster to market, less UI control), or a custom Device Policy Controller (full control, materially higher build cost)? This decision changes the entire technical scope and should be resolved before any P0 engineering starts. (engineering/product)
- What COPPA and child-data-privacy compliance requirements apply, given this product explicitly handles a child's device and usage data — a materially different compliance bar than the other two PRDs in this set? Needs legal review before any usage data leaves the device. (stakeholder/legal — blocks P0)
- Should budget units be time-based, open-count-based (matching the original token concept), or a blend of both? (product)
- How does this product avoid feeling like the "Social Shame Launcher" or "Charity Penalty" concepts it deliberately rejected, once real families start using it and edge cases appear? (product — ongoing design discipline, not a one-time decision)

## Timeline Considerations

- No hard external deadline. The build-vs-integrate decision (Family Link integration vs. custom Device Policy Controller) gates all further engineering scope and should be resolved first.
- Legal/compliance review (COPPA and equivalent) should begin in parallel with the clickable-prototype test, not after — this is the one PRD in this set with a materially higher compliance bar, and it should not be discovered late.
- P2's convergence with Family Command Center should be revisited explicitly once both products have independent traction, rather than assumed upfront.
