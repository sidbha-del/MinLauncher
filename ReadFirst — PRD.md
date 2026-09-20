# ReadFirst — PRD

2026-09-19 · MinLauncher. Renamed from "Book Launcher"; the package is `app.readfirst`.

**Lineage:** Second-ranked option (5.75/10) on the weighted scorecard from the three-panel brainstorm. The strongest evidence behind this concept is that two independent hobbyists already built versions of it (Readers Launcher, PageFlow) — proof the itch is real, though neither shows mainstream traction, which is neutral-to-negative evidence for a commercial product, not a strength. Its weakest score was Differentiation (5/10): the original spec never resolved why this needs to own the Android HOME role rather than existing as a great reading app plus a widget. This PRD resolves that by applying the same mechanism family that won Intent Queue — remove the app grid as the reflexive escape hatch — anchored to reading instead of task intentions, and by adding e-ink device sync as a second, harder-to-copy differentiator.

## Problem Statement

People who want to read more do not lack reading apps — they lack a phone whose default surface nudges them toward reading instead of toward whatever app is most habitual. A widget showing "currently reading" sits on a normal home screen next to the same app grid that competes for attention every time the phone is unlocked; it doesn't remove the competition, it just adds another tile to it.

Separately, people who own dedicated e-ink readers (e.g., XTEink X3/X4) currently manage two disconnected libraries and two disconnected reading-progress states — one on the phone, one on the e-ink device — synced only through third-party companion apps (e.g., CrossPoint Sync) or manual file transfer.

Cost of not solving: the reading intention stays one tap away from being overridden by a more habitual app, and e-ink device owners maintain reading progress and library state by hand across devices.

## Goals

1. Home screen defaults to a "currently reading" surface, with the standard app grid deliberately one gesture away rather than the first thing seen — applying the same grid-absence mechanism that differentiates Intent Queue, anchored to reading instead of tasks.
2. Library screen surfaces the full book collection (EPUB, PDF, MOBI, FB2, plain text) without requiring a separate reading app to browse it.
3. (Phase 2) Reading progress and library sync with e-ink reader devices, specifically XTEink hardware and any device supporting the KOReader sync protocol, so a book opened on the phone resumes at the correct page on the e-ink device and vice versa.
4. (Phase 3, contingent) Provision for this launcher's UI to run as the home-screen environment on compatible Android-based e-ink reader hardware directly, not only as a phone-side companion.

## Non-Goals

- No custom annotation/highlighting editor built from scratch in v1. Interoperate with the existing KOReader-compatible ecosystem rather than reinventing it.
- No book purchasing, storefront, or DRM-management flow in v1.
- No social/accountability layer (e.g., book-club visibility on the library screen). Explicitly cut during brainstorming as scope creep that contradicts the minimalist premise.
- No iOS. Android's HOME-role APIs make launcher replacement possible; iOS does not support it.
- No custom e-ink firmware or ROM work. Phase 3 targets devices that already run a compatible Android environment and expose a way to change the default launcher — it does not involve modifying reader firmware.

## User Stories

**Primary user — someone who wants to read more and is willing to let their phone nudge them toward it**

- As a user, I want my phone's home screen to show the book I'm currently reading and let me resume in one tap, so reading is the path of least resistance, not scrolling.
- As a user, I want the standard app grid to require a deliberate swipe rather than sit on the default surface, so I'm not constantly one glance away from a more habitual app.
- As a user, I want to see my full library without opening a separate reading app.

**Secondary user — someone who owns an e-ink reader alongside their phone**

- As a user, I want my reading progress to sync automatically between my phone and my XTEink device, so I don't lose my place switching between them.
- As a user, I want to send books from my phone's library to my e-ink device over local WiFi, without cables or a desktop app.

## Requirements

### P0 — Pure Book Launcher

| Requirement | Acceptance Criteria |
| --- | --- |
| Home = currently-reading screen | Cover, title, progress percentage, and a "resume" action that opens directly to the last-read page |
| App grid is deliberately secondary | Standard app drawer reachable via one consistent gesture (e.g., swipe up), never shown by default on Home |
| Library screen | Lists all books on-device, filterable/sortable, supports EPUB, PDF, MOBI, FB2, and plain text |
| Format rendering | Reflowable text (EPUB/MOBI/FB2/text) and fixed-layout (PDF) both render legibly without requiring a third-party reader app |
| Resume-where-you-left-off | Reopening a book from Library returns to the exact last-read position |

### P1 — E-ink Device Sync

| Requirement | Acceptance Criteria |
| --- | --- |
| KOReader-compatible progress sync | Reading position syncs to/from any device or app implementing the KOReader sync protocol, using either a self-hosted or user-chosen sync server |
| XTEink local WiFi library transfer | Books can be pushed from the phone's library to an XTEink device over local WiFi, with queued transfer that resumes automatically when the device reconnects (matching the pattern established by CrossPoint Sync) |
| Sync conflict handling | If progress differs between devices when they reconnect, the user is shown both positions and picks one — no silent overwrite |
| Sync is opt-in and off by default | No network activity related to sync occurs until the user explicitly connects a device |

### P2 — Launcher-on-Reader Provision (contingent on P0/P1 validating)

| Requirement | Acceptance Criteria |
| --- | --- |
| Compatible-hardware detection | App can detect whether it's running on an Android-based e-ink reader environment and adapt rendering (e.g., disable animations, reduce refresh-triggering redraws) |
| E-ink-safe rendering mode | UI avoids ghosting-prone partial refreshes; full-screen refresh cadence is configurable |
| Launcher-role provision on reader hardware | Where the reader device permits changing its default launcher, this app can be set as that device's home-screen environment, showing the same currently-reading/library model |

### Next — agreed order (2026-09-19)

| # | Feature | Status | Notes |
| --- | --- | --- | --- |
| 1 | Now listening on Home | Built | Reads active media sessions (Audible, Libby, Play Books, Kobo, Storytel, Everand, Kuku FM, Libro.fm); opt-in via notification access, which is used only for what's playing; audiobook apps only by default, "All audio" optional; −30s / play-pause / +30s |
| 1b | Other reading apps (handoff) | Built | Library section below the shelves listing installed Kindle, Play Books, Kobo, Libby, Everand, Pratilipi, Wattpad, Moon+, ReadEra, KOReader, Librera, Lithium, PocketBook; tap opens the app. No attempt to read DRM'd books or progress |
| 2 | Open Library metadata & covers | Built | Fills missing titles/authors/covers only; conservative matching (one-word titles need 10+ editions); one request per second; Settings toggle; per-book "Reset" undoes a match |
| 3 | OPDS catalog client | Built | OPDS 1.x: Project Gutenberg built in (browse, search, paging, merged per-edition book pages, EPUB3-first downloads into app storage), Standard Ebooks with Patrons Circle sign-in, any user-added Calibre/Kavita/OPDS server with Basic auth |
| 3b | Pause before distracting apps + Home time split | Built | Pause (5 s, "Read instead") before suggested feed apps (installed ones only; WhatsApp/LinkedIn excluded), user-editable. Home "Today" bar: Reading (reader timer + reading apps + audiobook playback) / Work / Scrolling / Other via Usage access. Only covers launches from ReadFirst |
| 3c | Phone check | Built | Home role lost, background restriction, lost folder access, Now listening unbound (auto-rebind), brand battery/autostart pages |
| 3d | Companion widget | Built | Resizable 4×2 → near full page on any launcher: where you stopped, Continue, today's read vs scroll, "+ Free books" (starter shelf → catalogs), "Make it my home screen". Funnel metric: widget users who switch to the full launcher |
| 4 | LibriVox pairing + built-in read-aloud | Planned | Public-domain audio for the same Gutenberg titles; text-to-speech for our own books keeps position in sync |

Out of bounds: shadow libraries (piracy, store rejection); reading Kindle/Libby book content or progress (DRM, no API — handoff only).

## Success Metrics

**Leading**

- Daily "resume reading" taps per active user.
- Time from cold home-screen launch to first page turn.
- % of users who reach the standard app grid vs. resume reading, on first unlock of the day (directional signal for whether the grid-absence mechanism is doing anything).

**Lagging**

- Self-reported pages/week read vs. a pre-launch baseline.
- Retention at 4+ weeks without reverting to a prior launcher.
- % of e-ink-device-owning users who enable sync (P1 adoption signal, once shipped).

**Riskiest assumption:** that people want their home screen — not just their reading app — dominated by one specific practice, rather than wanting a great reading app with a widget.

**Cheapest test:** ship the "currently reading" experience as a home-screen widget first, to real readers, before building the full launcher replacement. If they ask to go further than the widget, that's the signal to build P0. This is a *different* test shape than Intent Queue's, and deliberately so — Book Launcher's core value (glanceable reading state) is genuinely testable via widget, unlike Intent Queue's, which requires the app grid to be structurally absent to mean anything.

## Open Questions

- Which KOReader sync server should this depend on — self-hosted (better privacy, more setup friction) or a shared public instance (simpler, less private)? (product/privacy)
- Format-coverage priority if engineering time is constrained — EPUB/PDF first, MOBI/FB2 later? (engineering)
- Does Phase 3 (launcher-on-reader) require coordination with XTEink or other device makers, or can it ship as a sideloadable app users install themselves? (product/business)
- E-ink refresh-rate and ghosting behavior varies significantly by panel — how much per-device tuning is needed before Phase 3 is usable, not just technically functional? (engineering — blocks Phase 3 scope)

## Timeline Considerations

- No hard external deadline. Sequencing is evidence-gated: run the widget test before committing to P0 engineering.
- P1 (e-ink sync) is the differentiator that most separates this from the existing hobby-project competition (Readers Launcher, PageFlow, neither of which syncs with dedicated reader hardware) — prioritize it once P0's core premise is validated.
- P2 (launcher-on-reader) is a materially bigger platform bet than P0/P1 and should not be scheduled until both have shipped and shown real retention.
