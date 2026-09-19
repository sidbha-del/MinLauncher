# Review of the two Gemini masterplans

2026-09-19 · reviews `MASTERPLAN.md` and `CLAUDE_UI_UX_MASTERPLAN.md` against the code and against what was actually tested.

Both plans say the same thing twice: the UI/UX plan is a subset of the commercial masterplan. Treat them as one plan.

## Verdict in one paragraph

The **diagnosis is mostly right, and the prescription is mostly premature**. Three findings are real and cheap to fix: Settings is hard to find, first launch is empty, and PDFs ignore the page colour. Two ideas are worth building soon: minutes-left-in-chapter, and friction before chosen distracting apps. Everything else is feature work that delays launch without testing the one thing that matters, which is whether people keep a book as their home screen for four weeks. Several "facts" in the plans are wrong and must not be repeated publicly.

## Claims that are wrong or unverified: do not repeat these

| Claim in the plans | What we actually know |
| --- | --- |
| Tested on Samsung Galaxy M42 5G, Android 13 | **True.** The SM-M426B (Android 13) is connected and has v0.1.0 installed. Our own testing was on a Realme RMX2117 (Android 12) and an API 36 emulator, so there are three devices in total. |
| "7 MB APK" | **Wrong.** Debug APK is 1.09 MB; the **release APK is 147 KB** (release build smoke-tested on the M42). The plan understates our real advantage. |
| "Sub-50 ms cold start", "zero crashes over multi-hour testing" | Not measured by us. Don't publish until measured (e.g. `adb shell am start -W`). |
| "174-app drawer" | Plausible on that Samsung (151 user-installed apps), but it describes one phone. |
| r/digitaldetox 250k, r/dumbphones 180k members | Unsourced. Check the numbers before quoting them. |
| "Add `onSwipeDown` to GestureFrame" | Already exists (`GestureFrame.kt:16`, dispatched at lines 38 and 58). Only the binding was missing. |
| "PDF has no night mode" | Half right: `applyPdfFilter()` only applied in Black ink, so a Night page with Color ink left PDFs white. It was a gating bug. **Fixed in this pass.** |
| TXT "already has 100% EPUB parity" | Correct. `ReaderActivity.openMenu()` shows Text/Font/Margins when `flow != null`, which covers EPUB and TXT. Only PDF lacked controls. |

## Item-by-item

| # | Proposal | Verdict | Why |
| --- | --- | --- | --- |
| 2 | Settings reachable from Home and Library | **Do now (done)** | Real gap: Settings was only in Apps → gear. The plan's gesture is wrong: on Pixel, Nova and Niagara, **swipe down on home = notification shade**, not settings. Built: long-press Home → quick sheet (Settings, ink, page, add books); gear in Library; swipe down → notifications. |
| 3 | Starter classics during setup | **Do now (done)** | The biggest first-run drop-off, and Play reviewers also open an empty app. Built as a 1-tap "Start with a free classic" shelf in setup and in the empty Home. Includes *Gitanjali* for the Indian audience. |
| 4 | 110 dp cover with drop shadow; excerpt cut to 2–3 lines | **Partly reject** | Drop shadows break the flat Ink & Paper design and ghost on e-ink. Cutting the excerpt removes the product's signature, "unlock and you're already reading". Keep the excerpt at about 5 lines; moving *Continue* into thumb reach is a fair point. |
| 4 | Anchor "Continue reading" low | **Do soon** | A real one-handed reach issue on 20:9 phones. Small change. |
| 5 | Minutes left in chapter (reading speed) | **Do soon (next)** | High value; it makes a long book feel bite-sized. About 50 lines: reading speed from page-turn timing, discarding flicks and idle pages. |
| 5 | Daily streak flame | **Reject as specified** | Loss-framed streaks are the dopamine mechanic this product sells against, and they cause guilt-uninstalls. Use a neutral "14 min today" instead, with no penalty for a missed day. |
| 5 | Chapter-complete celebration card | **Defer** | A nice touch, but not what decides retention. |
| 6 | 2-second pause before the **whole** app drawer | **Change it, then do it** | Pausing the drawer also slows Phone, Maps and UPI, and people uninstall over that. The version that works (the "one sec" pattern) puts friction only on apps **the user marks** as distracting (Instagram, YouTube…), with "Read one page instead". This is the feature that answers the PRD's riskiest assumption ("why not a reading app plus a widget?"). Build it right after launch. |
| 7 | PDF comfort suite | **Do the cheap 2 (done)**, defer 3 | Built: PDF follows the page (Night, Sepia and Paper remap page colours) and **trims margins** (≈30% larger text on phones). Deferred: fit-width with scrolling, contrast stepper, landscape lock. |
| 8 | Highlights, dictionary | **Defer** | Both need text selection in our custom page view, a week of work. When built, use the system `ACTION_PROCESS_TEXT` (offline dictionaries, Translate) before any online API. |
| 9 | Quote cards (viral loop) | **Defer to v1.1** | A good growth loop, but it also needs text selection. A cheaper v1 alternative is "Share progress card" (cover, title, minutes read) with no selection needed. |
| 10 | Go-live checklist | **Incomplete** | Misses the blockers: new-account closed-testing rule, keystore, privacy policy, data safety, notification-listener review risk, package-name permanence. See `PLAYSTORE_CHECKLIST.md`. |
| 11 | GTM | **Reasonable, generic** | See `LAUNCH_PROMOTION.md` for sharper, cheaper plays. |
| 12 | Freemium + $1.49/mo subscription | **Reject for launch** | A subscription on a launcher before product-market fit adds Play Billing, refund handling and review surface for no learning. Launch free; add a one-time "Supporter" unlock (fonts, textures) once four-week retention is known. |
| 13 | 6-week Gantt | **Reorder** | Launch first. Then per-app friction, minutes-left, share card, and selection (highlights, define, quote). |

## Most value-add steps, in order

1. **Ship v1 to a testing track now**: fixes in this pass, then the Play checklist.
2. **Per-app mindful friction**: the differentiator, and the answer to "why a launcher".
3. **Minutes left in chapter** and "min read today" on Home.
4. **Measure 4-week retention** from 20–50 real testers before any monetisation or new formats.
5. Then text selection, which unlocks highlights, define and quote cards together.
