# Play Store go-live checklist

2026-09-19 · Book Launcher first, MIRA second. Owner column: **You** = needs your account or decision, **Code** = done in the repo.

## 0. The blocker that decides "today"

Open Play Console → **Home → your developer account → "Production access"** (or Dashboard).

- **Personal account created after 13 Nov 2023:** Google requires a **closed test with at least 12 testers opted in for 14 continuous days** before you can apply for production. "Today" means *closed testing live today, production in about 3 weeks*.
- **Older account, or an organisation account:** you can submit to production today. First review of a new app usually takes a few days.

Either way the work below is the same. Start the closed test **today**, because the 14-day clock is the long pole.

**Answer (2026-09-19):** the developer account was created in 2026. If it's a **personal** account, closed testing applies: internal and closed testing go live today, and production is possible from about day 15. If it was registered as an **organisation**, production is available now. Check Play Console → Settings → Developer account → Account details.

## 1. Decisions that are permanent (do before the first upload)

| # | Task | Owner | Notes |
| --- | --- | --- | --- |
| 1.1 | **Package name** | You | Currently `app.booklauncher`. It can never change after upload. Prefer a name you control, e.g. `com.<yourdomain>.booklauncher`. Check that it's free by trying to create the app. |
| 1.2 | **App name on Play** | You | "Book Launcher" is generic and may clash in search. Options: "Book Launcher: Read First", "Shelf: Book Home Screen". Max 30 chars. |
| 1.3 | **Upload key** | **Done** | `android/booklauncher-upload.jks` + `android/keystore.properties` (random password, both git-ignored, never pushed). **Copy both files to two safe places** (password manager + a private drive). With Play App Signing (the default), Google holds the real app key, so a lost upload key can be reset through Play support. Signed bundle: `android/app/build/outputs/bundle/release/app-release.aab`. |
| 1.4 | **Support email** | You | Required and public on the listing. Use a support alias, not your personal Gmail. |

## 2. Build (Code, same day)

| # | Task | Status |
| --- | --- | --- |
| 2.1 | Version control | **Done**: private repo github.com/sidbha-del/MinLauncher |
| 2.2 | `versionCode 1`, `versionName 1.0.0` | Done in this pass |
| 2.3 | targetSdk 36, minSdk 26, R8 + resource shrinking on | Already set |
| 2.4 | Debug-only test hooks excluded from release (`src/debug/`) | Already so |
| 2.5 | `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab` | After 1.3 |
| 2.6 | Smoke-test the release build on the phone (R8 can break things debug doesn't show): home, open EPUB/PDF/TXT, catalog download, Now listening | After 2.5 |

## 3. Policy and store forms (You, ~2 hours)

| # | Form | What to answer |
| --- | --- | --- |
| 3.1 | **Privacy policy URL** | Required. A draft is in `store/privacy-policy.md`. Host it on GitHub Pages / Google Sites. |
| 3.2 | **Data safety** | No data collected or shared. Network use: book titles sent to Open Library for cover lookup (optional, can be switched off); downloads from Gutenberg / Standard Ebooks / user-added catalogs. No account, no analytics, no ads. Data stays on the device. |
| 3.3 | **Ads** | No ads. |
| 3.4 | **App access** | All features available without login (Standard Ebooks sign-in is optional). |
| 3.5 | **Content rating** | IARC questionnaire: reference/books, no user-generated content shared → Everyone. Public-domain catalogs contain classic literature; answer honestly on mature themes. |
| 3.6 | **Target audience** | 13+ (avoid the "Designed for Families" rules). |
| 3.7 | **Notification access** | Our `NotificationListenerService` only reads **media sessions** for Now listening. It isn't on the permission-declaration list, but reviewers do check it, so describe it in the listing and privacy policy. If review objects, ship 1.0 with Now listening hidden and add it in 1.1. |
| 3.8 | **Category** | Books & Reference. A launcher also appears under Personalization, but Books is less crowded and matches the promise. |

## 4. Store listing assets (You + me, ~2 hours)

| # | Asset | Spec |
| --- | --- | --- |
| 4.1 | App icon | 512×512 PNG (export from the adaptive icon) |
| 4.2 | Feature graphic | 1024×500 |
| 4.3 | Phone screenshots | 2–8, 9:16. Use: Home (Now reading), Shelf, Stack, Reader in Night, Black ink, Gutenberg catalog, Now listening |
| 4.4 | Short description | 80 chars: *"Unlock your phone into your book. A calm home screen that makes reading first."* |
| 4.5 | Full description | Draft in `store/listing.md` |

## 5. Tracks

| # | Track | When |
| --- | --- | --- |
| 5.1 | **Internal testing** (up to 100, instant, no review wait) | Today: upload the .aab, add your own emails |
| 5.2 | **Closed testing** with 12+ opted-in testers | Today. Recruit from friends, a WhatsApp group, r/androidapps beta threads. Testers must stay opted in for 14 days. |
| 5.3 | Apply for production | Day 15, with answers about what testers found |
| 5.4 | Production, staged rollout 20% → 100% | After approval |

## MIRA (second, harder)

MIRA asks for **call, SMS and location** permissions. SMS and Call Log are restricted on Play: you must file the **Permissions Declaration Form**, and approval isn't guaranteed. Background location needs its own declaration and video. Plan MIRA's submission as a separate 1–2 week track. Options if SMS is refused: send SOS through the phone's SMS app with an `ACTION_SENDTO` intent (one tap to confirm), or through the backend. Don't block Book Launcher on MIRA.
