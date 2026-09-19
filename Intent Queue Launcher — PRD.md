# Intent Queue Launcher — PRD

2026-09-19 · MinLauncher

**Lineage:** Winner of a three-panel brainstorm (this session, ChatGPT, Gemini) and the top-ranked option (7.50/10) on the weighted scorecard across TAM, technical feasibility, market demand, competition, differentiation, and monetization. Chosen over four other finalists because it is the only one with a structural reason to own the Android HOME role rather than existing as an app plus a widget: the mechanism depends on the stock app grid being *absent*, not merely scrolled past.

## Problem Statement

Smartphone home screens organize around apps ("which app do I open?") when the actual question users have is what to do next. This mismatch is not hypothetical: a Pew Research Center survey of 9,750 U.S. adults (May 26–June 1, 2026) found 53% say they spend too much time on their phone, 45% had tried to cut back in the prior year, and 70% of 18–29-year-olds say the same. Existing minimalist launchers (Niagara, Olauncher, Before) reduce visual clutter but keep the same underlying unit of organization — apps in a list. None reorganizes the home screen around intentions instead.

Cost of not solving: users keep defaulting to reflexive app-opening because the app grid is always one glance away, and existing digital-wellbeing tools (Opal, One Sec, Freedom-style blockers) fail for the same structural reason a token-budget launcher does — the same person who wants restraint also holds the ability to remove the restriction in a few taps.

## Goals

1. Replace "which app do I open" with "what am I trying to do" as the home screen's organizing question. Target: validated in the killer test below before any further build investment.
2. Prove the interaction model survives real use without the safety net of a visible app grid. Target: defined by the kill criteria in Success Metrics.
3. (Phase 2) Layer in smart suggestions (calendar, notification-derived) without becoming a covert data-collection product. Target: gated behind a background-service-survival spike, not shipped speculatively.
4. Support a sustainable freemium-to-paid model consistent with what the category already proves people will pay for (Niagara, Smart Launcher).

## Non-Goals

- No AI-interpreted natural-language actions in v1 ("Option C: AI Action Queue"). Reliable action execution is a harder problem than generating good suggestions, and v1 should not depend on it.
- No recurring workflow/automation engine in v1 ("Option D: Personal Workflow OS"). That is a different, larger product once the core premise is validated.
- No calendar or notification integration in P0. That is P1, and only after the background-service-survival risk is resolved.
- No iOS. Android's launcher/HOME-role APIs make this possible; iOS does not support home-screen replacement.
- No social or shared-queue features. Single-user product for v1.

## User Stories

**Primary user — someone who feels overwhelmed by apps but isn't ready for a fully minimalist/dumbphone experience**

- As a user, I want my home screen to show what I intend to do right now, so I don't default to opening whatever app is most habitual.
- As a user, I want completing an intention to remove it from my screen, so my home screen reflects my actual day, not a static list.
- As a user, I want a way to still find any app when I genuinely need one, so the product never traps me.
- As a user, I want the system to suggest — never silently decide — what belongs in my queue, so I don't feel surveilled by my own launcher.

## Requirements

### P0 — Manual Intent Queue (Option A)

| Requirement | Acceptance Criteria |
| --- | --- |
| Home screen shows Now / Next / Later sections | No app grid visible on the default Home surface; sections populate from user-entered intentions |
| Manual intention entry | User can add a plain-text intention and optionally attach it to an app/shortcut/deep link |
| Tap-to-launch | Tapping an intention launches the attached action within one tap |
| Mark-done removes from queue | Completing an intention removes it from Home immediately, no confirmation dialog |
| Full app drawer remains reachable | A dedicated search/all-apps affordance exists, reachable in one gesture, so the user is never fully blocked from any app |
| No special permissions required for P0 | Ships using only `PackageManager` and standard shortcut/intent APIs — no usage-access or notification-listener permission prompts |

### P1 — Smart Queue Lite (Option B), gated

| Requirement | Acceptance Criteria |
| --- | --- |
| Background-service survival spike (prerequisite) | A headless logging service survives 72 continuous hours on a Samsung and a Xiaomi device without being killed by OEM battery optimization, before any P1 feature ships |
| Calendar-derived suggestions | Upcoming calendar events surface as suggested queue items with time-to-event context |
| Notification-derived suggestions | User-approved notification listener surfaces unreplied threads as suggested (not auto-added) queue items |
| Suggestions are opt-in per source | User can disable calendar or notification suggestions independently without losing P0 functionality |
| Explainability | Every suggested item states why it appeared (e.g., "meeting in 18 minutes") |

### P2 — Future, out of scope until P0/P1 validate

- AI-interpreted natural-language actions ("I need to prepare for tomorrow's trip" → itinerary of actions)
- Recurring workflow/automation engine (personal workflow OS)

## Success Metrics

**Leading (the killer test — run before full build)**

- Prototype must actually take the Android HOME role for the trial group during the test window. A widget-only prototype cannot validate this concept, because it leaves the stock app grid one swipe away — exactly the condition the mechanism requires to be absent.
- Recruit 20 Android users who regularly use calendars/tasks; run for 3 days with manual-entry-only functionality (P0 scope), full app drawer still available via search.
- **Kill criteria** (any one triggers a kill, not a redesign-and-retry):
  1. Fewer than 8 of 20 voluntarily use the queue multiple times per day, or
  2. More than 40% routinely bypass it and go straight to the app drawer, or
  3. Fewer than 5 of 20 say they would actually replace their current home screen with it.

**Lagging (post-validation)**

- Sustained daily use at 4+ weeks without reverting to a prior launcher.
- Self-reported reduction in reflexive app-opening, measured against a pre-launch baseline.

## Open Questions

- Build-vs-integrate is not in question here (P0 has no dependency), but the **background-service survival** result gates all of P1 — do not schedule P1 work before that spike completes. (engineering)
- What is the safest way to run a real HOME-role test with 20 external users, including a one-tap "restore my previous launcher" safety net, given that changing the default launcher is not casually reversible for a non-technical tester? (product/engineering)
- At what point does "smart suggestions" data handling require a privacy policy and data-safety disclosure beyond what a P0-only product needs? (stakeholder — relevant only once P1 is scheduled)
- Should P1's notification-listener scope be limited to specific user-selected apps, to minimize both privacy exposure and permission-prompt friction? (product)

## Timeline Considerations

- No hard external deadline. Sequencing is evidence-gated: do not begin P1 engineering (calendar/notification signals) until the background-service-survival spike passes on both a Samsung and a Xiaomi device.
- The 20-user, 3-day killer test is the go/no-go gate before any further investment beyond the P0 prototype. This is not a validation exercise to declare success on a strong showing — a failing result is meant to kill the idea, not prompt an iteration loop with the same core mechanism.
- Family Command Center and Private Personal Index (the two other ChatGPT-panel finalists) remain parked, not pursued, unless Intent Queue fails its killer test.
