# MandateShield — Hackathon Playbook

**Status:** Working prototype, not yet a submission.
**Audit date:** Saturday, 21 November 2026.
**Target:** Everything in §4 shipped and rehearsed by **12:00 PM IST, Sunday 22 November 2026**.
**Audience:** the builder (you), plus any judge who reads this after the demo.

This document is deliberately blunt. It separates what is *provably working* from what is *theatre*, because the single fastest way to lose a hackathon is to be caught overclaiming in the Q&A. Every claim below is either marked **VERIFIED** (I ran it), **READ** (I read the code and it follows), or **CLAIMED** (documentation asserts it, unverified).

---

## 0. The one sentence

> **By 12:00 PM IST on 22 November 2026, MandateShield is done when a first-time judge on a public HTTPS URL can register, load transactions into a real MongoDB database, watch the real backend engines resolve and group every recurring payment, open one item and hear a Sarvam-generated Hinglish renewal briefing, generate a cancellation notice, and see that item move to "requested" with a persisted audit entry — with zero hardcoded results anywhere in that path, and a one-click reset so the next judge starts clean.**

That is the whole document compressed. Everything below either serves that sentence or is cut.

---

## 1. Executive summary — the 60-second version

**What exists.** A Spring Boot 4 / Java 25 / MongoDB backend with three genuine engines (merchant descriptor resolution, recurring-payment discovery, anomaly detection), JWT auth with per-user data scoping, a React 19 dashboard, a cancellation-guidance module, and a synthetic evaluation harness. It builds, it tests, it runs, and I have driven it end-to-end over HTTP. On the seeded dataset it finds 5 recurring groups across 20 transactions, flags all 4 planted billing scenarios, and computes ₹14,244 annualised spend against ₹6,828 of identified savings.

**What does not exist.** No live URL, no container, no CI (despite the README), no bank or Account Aggregator connection, no reminders of any kind, no multi-currency handling, no audit trail, no rate limiting, and several places where the frontend invents a plausible-looking answer when the API fails.

**What we do about it.** Four P0 correctness fixes (stale subscriptions, resolver side-effects, currency, fake fallbacks), one differentiator (Sarvam AI Hinglish voice + Indic descriptor understanding), one deployment, one honest evaluation page. Everything else is P1/P2 and gets cut without regret.

**Why this can win.** The category (subscription tracking) is crowded globally and nearly empty in India at the descriptor level. Rocket Money does not parse `RAZORPAY*SPOTIFYIN` or `NACH DR HDFC LIFE`. Nobody in the Indian market pairs *evidence-linked* recurring-payment detection with *guided* cancellation in the user's own language. That is a defensible, demoable wedge, and it is already 70% built.

---

## 2. How to read this document

| Tag | Meaning |
|---|---|
| **VERIFIED** | I executed it and observed the result in this session. |
| **READ** | Confirmed by reading source. Behaviour follows from the code, not from a live run. |
| **CLAIMED** | Asserted in README/PPT. Not verified. Treat as a to-do until proven. |
| **GAP** | Known absent. Do not claim it on stage. |

Rule for the pitch: **never say a CLAIMED or GAP item in the present tense.** Say "next" or "designed for", or don't say it.

---

## 3. Where we are today

### 3.1 Verified working

These are safe to demo and safe to claim.

| # | Capability | Evidence | Tag |
|---|---|---|---|
| 1 | Backend compiles and boots on Java 25 / Spring Boot 4.1.1 | `mvnw spring-boot:run`; `/actuator/health` → `{"status":"UP"}` | VERIFIED |
| 2 | Test suite green | 9 tests: `DescriptorMatchingAndFeaturesTest` (8) + `RecurringDiscoveryEngineTest` (1) | VERIFIED |
| 3 | Frontend lint + production build clean | `npm run lint`, `npm run build` both pass | VERIFIED |
| 4 | Registration and login issue a working JWT | `POST /api/v1/auth/register` → 201 + token; `GET /api/v1/auth/me` returns the user | VERIFIED |
| 5 | Auth is actually enforced | Unauthenticated `GET /api/v1/dashboard` → **401** | VERIFIED |
| 6 | Duplicate email rejected | Second register with same email → **409** | VERIFIED |
| 7 | Cross-user data isolation | User B requesting User A's subscription → **404**, not 403 (no existence leak) | VERIFIED |
| 8 | Input validation | Empty descriptor → **400**; negative amount → **400** | VERIFIED |
| 9 | Descriptor resolution | `SPOTIFY INDIA MUMBAI` → Spotify / Entertainment / UPI / TIER_1 / 0.96 | VERIFIED |
| 10 | Synthetic seed produces the advertised shape | 20 transactions, 20/20 merchant labels, 5/5 recurring groups, 4/4 scenarios | VERIFIED |
| 11 | Dashboard KPIs compute | Annualised ₹14,244; identified savings ₹6,828; 3 active + 2 tentative; 6 anomalies | VERIFIED |
| 12 | Recurring discovery thresholds work | Interval scoring separates active from tentative; unit-tested | READ |
| 13 | Anomaly engine covers 5 signals | Price hike, trial conversion, annual renewal, resumed billing, user-reported unused | READ |
| 14 | Auto-learning pattern write-back | Tier-2/3 resolutions persist a learned pattern for next time | READ |
| 15 | Cancellation state machine | `request` / `confirm` / `revoke` / `report-unused` / `confirm-activity` all persist per-user | READ |
| 16 | Problem-detail error shape | Spring `ProblemDetail` with `detail`, consistent across handlers | READ |

### 3.2 Verified broken

Each of these is reproducible. Fix or disclose; do not let a judge find them first.

> A second audit pass (reading the import path, indexes, DTOs and controllers line by line) found another cluster of defects — including one that silently double-counts a brand. They are catalogued with fixes in **§4.5**, next to their remedies, so this section stays a record of what the first run-through exposed.

**B1 — Deleting transactions leaves zombie subscriptions. (Severity: high)**
Repro: seed demo → delete every Netflix transaction → `POST /api/v1/pipeline/run` → Netflix still appears in the recurring list and annual spend is unchanged.
Cause: `MandateShieldPipelineService` only upserts. There is no reconciliation pass that marks or deletes subscriptions whose supporting transactions vanished.
Why it matters: it is the first thing a sceptical judge tries ("what if I delete something?"). It also makes the savings number a lie.

**B2 — The resolver playground writes into the user's real history. (Severity: high)**
Repro: note transaction count → `POST /api/v1/resolver/resolve` with a novel descriptor → transaction count increases.
Cause: `MerchantResolverEngine` persists a `TransactionDocument` as part of resolution.
Why it matters: a "try it" box that silently mutates the dataset is a trust bug and it corrupts the demo mid-pitch.

**B3 — Multi-currency is summed as if everything were rupees. (Severity: high)**
Repro: add a USD 20 subscription → annualised spend rises by **₹240**.
Cause: `SubscriptionDocument` has no currency field; `ExecutiveDashboardService` multiplies `amount × cycles` with no conversion.
Why it matters: one USD-priced tool (Figma, Notion, Adobe, AWS) makes the headline number wrong by ~85×.

**B4 — The demo evaluation is self-fulfilling. (Severity: medium-high, reputational)**
Cause: `SyntheticEvaluationService` seeds descriptors that already exist as Tier-1 patterns in the same database, then scores against those labels. 20/20 is guaranteed by construction.
Why it matters: if a judge asks "isn't this circular?", the honest answer today is "yes". Fix with a holdout set (§4.3, P0-5) or disclose on the metrics slide.

**B5 — The frontend fabricates resolver results on API failure. (Severity: high, integrity)**
Location: `frontend/src/pages/ResolverPlayground.jsx`, `catch` branch.
On any network error it renders `confidenceScore: 0.85`, `resolutionTier: 'TIER_2'`, `matchedPattern: 'HEURISTIC_FALLBACK'` — a result that no backend produced. A judge with dev tools open sees a green-looking answer during an outage.
Why it matters: this is the single most dangerous line of code in the repo for a judged event. Delete it. Show an error.

**B6 — Static playbooks claim to be freshly reviewed. (Severity: low, but dishonest)**
`reviewedOn` is set to `now` at request time, so guidance authored months ago always looks current.

**B7 — Cancellation guidance is Netflix-shaped. (Severity: medium)**
`CancellationAndRevocationService` contains a Netflix-specific branch and a small set of static playbooks. Any merchant outside that set gets generic text that may not match reality.

**B8 — CORS is pinned to localhost. (Severity: blocking for deployment)**
`CorsConfig` allows local origins only. A deployed Vercel frontend will fail preflight until this becomes environment-driven.

**B9 — README claims CI that does not exist. (Severity: low, embarrassing)**
`.github/` is absent. `README.md` line 261 lists `.github/workflows/ci.yml`. First `git log` check by a judge kills credibility.

**B10 — `target/` is tracked (36 files). (Severity: cosmetic)**
`.gitignore` exists but the build output is committed. Looks careless.

**B11 — The sample-data toggle is a half-truth. (Severity: low, UX)**
`App.jsx` mounts `ResolverPlayground` and `CancellationStudio` with `isDemoMode={false}` hardcoded, so the "Switch to Sample Data" control in the header does not affect those two pages.

### 3.3 Mocked, hardcoded, or missing

**Mocked / hardcoded**

| Area | What is fake | Where |
|---|---|---|
| Resolver UI | Client-side "high fidelity" mock resolution with fixed 0.96 confidence | `ResolverPlayground.jsx` demo branch |
| Resolver UI | Fake 0.85-confidence fallback on error | `ResolverPlayground.jsx` catch branch |
| Cancellation UI | Demo letter generation without a backend call | `CancellationStudio.jsx` demo branch |
| Cancellation UI | Hardcoded merchant presets and mandate refs | `CancellationStudio.jsx`, `data/sampleData.js` |
| Cancellation backend | Netflix-specific logic; static playbooks with `reviewedOn = now` | `CancellationAndRevocationService` |
| Evaluation | Self-scored against pre-seeded patterns | `SyntheticEvaluationService` |
| Sample mode | Fixed personas and transactions | `frontend/src/data/sampleData.js` |

**Missing entirely**

- Live deployment (no Dockerfile, no platform config, no live URL).
- CI (no `.github/`).
- Bank / Account Aggregator / UPI AutoPay ingestion. Transactions are user-supplied, CSV, or synthetic.
- Any notification channel: email, push, WhatsApp, SMS. Zero.
- Reminders and renewal calendar.
- Multi-currency support and FX.
- Password reset, email verification, MFA.
- Rate limiting / abuse protection.
- Pagination on list endpoints.
- Account deletion and data export (a real privacy requirement in India under DPDP).
- Audit trail of user actions.
- Deep-link routing in the frontend (single-page state switching).
- Real merchant playbook coverage beyond a handful of brands.
- Actual cancellation execution. **By design** — see §8.4. We guide and track; we do not cancel.

### 3.4 Capability matrix (honest)

| Claim | Reality | Stage-safe wording |
|---|---|---|
| "Detects recurring payments" | True for supplied data | "Detects recurring payments from transaction data you provide" |
| "Resolves merchant descriptors" | True, 3 tiers, with learned patterns | Keep as-is |
| "AI enrichment" | Optional Gemini/Pinecone, off by default | "Optional enrichment, not required for the core demo" |
| "Cancels subscriptions" | **False** | "Guides and tracks cancellation" |
| "Connects to your bank" | **False** | "Import a CSV today; Account Aggregator next" |
| "Available in Indian languages" | **False today**; Sarvam integration planned | "Hinglish voice briefing — shipping today" only after §4 |
| "Production ready" | **False** | "Working prototype, deployed for judging" |
| "CI on every push" | **False** | Remove from README or add the workflow |

---

## 4. Where we will be by 12:00 PM, 22 November 2026

### 4.1 Definition of done, expanded and testable

The product is done when **all twelve** of these pass on the **production URL**, in one continuous run, on a **fresh account**, with the browser console open:

1. Register a new account with an email never used before. No seeded data appears.
2. Import a 20-row CSV (or press "Load demo dataset"). Every row is stored in MongoDB with the correct owner.
3. `POST /pipeline/run` produces ≥1 recurring group and the response is non-empty on first attempt.
4. Every recurring item shows a merchant, a category, an amount, a cadence, a next-charge date, and a confidence tier.
5. The dashboard's annualised total equals the sum of `amount × cycles_per_year` for active items, computed in INR, with non-INR items converted at a stored rate.
6. Deleting all transactions for one merchant and re-running the pipeline removes that merchant from the recurring list within one run.
7. Opening an item shows the **evidence**: the raw descriptors, dates, and amounts that justify it.
8. Pressing "Brief me" plays Sarvam-generated audio in Hinglish that names the actual item and the actual next-charge date from the database.
9. Generating a cancellation notice returns merchant-specific text and stores the letter against the item.
10. Marking the item "requested" persists state and appends an audit row with actor, timestamp, and previous state.
11. Killing the backend mid-session produces a visible error state in the UI — never a fabricated success.
12. A reset control returns the account to empty in one click, and a second judge can repeat steps 1–11 without intervention.

If any of those twelve fails at 11:00 AM, we cut scope, not honesty.

### 4.2 The 22-hour plan

Today is Saturday 21 November. The window is roughly **14:00 Sat → 12:00 Sun**. Times are IST.

| Block | Window | Work | Exit criterion |
|---|---|---|---|
| 0 | Sat 14:00–15:00 | Freeze. Branch `release/hack`. Tag `v0.9-pre-hack`. Create `demo@mandateshield.app` account. Screenshot current state for the "before" slide. | Tag exists; no new features merged after this without a P0 label |
| 1 | Sat 15:00–17:30 | **P0-1** reconciliation pass. **P0-2** resolver dry-run. | B1 and B2 repro scripts now pass |
| 2 | Sat 17:30–19:30 | **P0-3** currency field + FX. **P0-4** delete fake fallbacks. | B3 and B5 gone; grep for `HEURISTIC_FALLBACK` returns nothing |
| 3 | Sat 19:30–21:00 | **P0-6** Sarvam spike: translate + chat + TTS behind a feature flag. | One real audio file generated from real DB data |
| 4 | Sat 21:00–22:30 | **P0-7** renewal calendar + in-app reminders. **P0-8** audit trail. | Both visible in UI and persisted |
| 5 | Sat 22:30–00:30 | **P0-9** deployment: Dockerfile, backend host, Vercel, Atlas network, env-driven CORS. | Public URL serves the app over HTTPS |
| — | Sun 00:30–06:00 | **Sleep.** Non-negotiable. Tired deployment debugging costs more than it saves. | — |
| 6 | Sun 06:00–08:00 | **P0-10** CI workflow + **P0-11** demo reset endpoint + seed hardening. | Green check on GitHub; reset works twice in a row |
| 7 | Sun 08:00–09:30 | **P0-5** holdout evaluation + honest metrics page. | Metrics page states method and limitations |
| 8 | Sun 09:30–10:30 | UX: empty states, skeletons, error toasts, mobile pass, copy edit. | No dead ends; no lorem; usable at 390px |
| 9 | Sun 10:30–11:15 | **Two full dress rehearsals** on production, fresh account each time, timer running. | Both under 7:00 with zero console errors |
| 10 | Sun 11:15–11:45 | Freeze. Record a backup video of the full flow. Export slides. | Video plays offline |
| 11 | Sun 11:45–12:00 | Submit. | Confirmation on screen |

**Rule:** if a block overruns by 45 minutes, cut the *next* block's lowest-priority item, not sleep, and not rehearsal.

**Where the second-pass fixes fit.** §4.5 lists ~26 hours of additional work found by reading the import path, indexes and DTOs line by line. Do not add it on top of the table above — it will not fit. Fold it in by substitution:

| Block | Swap out | Swap in |
|---|---|---|
| 1 | nothing | **A1** (merchant keys) before P0-1 — same file, same mental model |
| 2 | nothing; extend by 45m, taken from block 4 | **A2** (atomic import) + **A3** (CSV formats) |
| 3 | nothing | **C1** (next-charge date) before the Sarvam spike — the briefing needs a real date to read out |
| 4 | P0-7 full calendar UI — keep only the "next 7 days" count that C1 gives you for free | **C4** (dismiss/confirm) |
| 8 | generic "UX polish" | **C7** (landing + demo entry), **C2** (cancel URLs), **C8** (error boundary) |
| 11 | nothing | **E5 + D5** (README truth) before submitting |

The reasoning behind the block-4 swap: a calendar grid is mostly layout work, while the *fact* a calendar communicates — when the next charge lands — is one computed field. C1 buys 80% of the value for 20% of the effort, and the freed hour goes to making the anomaly model accountable, which judges will actually test.

### 4.3 Deliverable specs

Each item is written as a spec with an acceptance test, not a task list, so you can tell when it is actually done.

---

**P0-1 — Subscription reconciliation (fixes B1)**

*Spec.* After every pipeline run, compute the set of subscriptions that the current transaction history still supports. Any subscription with zero supporting transactions in the lookback window is marked `ORPHANED` (preferred) or deleted. Orphaning preserves the audit trail and is the better story: "we don't erase history, we stop counting it."

*Design notes.* Add a `supportingTransactionIds` list or a `lastSupportedAt` timestamp to the subscription document. Reconciliation compares `lastSupportedAt` to the newest pipeline run. Do **not** delete user-confirmed cancellations — those should remain visible as history.

*Acceptance.* Seed → delete all Netflix transactions → run pipeline → Netflix absent from the active list; annualised total drops by exactly the Netflix contribution; a second run is idempotent (no further change).

*Risk if skipped.* Judge deletes one row and the whole dashboard becomes indefensible.

---

**P0-2 — Resolver becomes dry-run by default (fixes B2)**

*Spec.* `POST /api/v1/resolver/resolve` must not write a transaction. Add an explicit `persist` flag defaulting to `false`. When `true`, require a full transaction payload and return the created transaction ID.

*Design notes.* The engine should expose a pure `resolve(descriptor, context)` path and a separate `resolveAndRecord(...)` used only by the ingestion path. Keep the learned-pattern write-back — that is a feature, and it does not pollute transaction history.

*Acceptance.* Resolve 10 descriptors from the playground → transaction count unchanged → `GET /transactions` identical. Ingest the same descriptors through `/transactions` → count increases by 10.

---

**P0-3 — Currency correctness (fixes B3)**

*Spec.* Add `currency` (ISO-4217, default `INR`) and `amountInInr` to the subscription document. Convert at ingestion using a stored rate with a `rateAsOf` timestamp. Never sum raw amounts across currencies.

*Design notes.* A static rate table refreshed daily is fine for a hackathon; label it as such. Do not call a live FX API during the demo — network flakiness is a worse failure than a one-day-old rate. Display the original amount and the INR equivalent so the user can see the conversion.

*Acceptance.* Add a USD 20/month subscription at a documented rate → dashboard increases by the converted INR amount, not ₹240. Mixed-currency dashboard shows a footnote with the rate and date.

---

**P0-4 — Remove fabricated results (fixes B5, B6)**

*Spec.* Delete the client-side fallback that returns confidence 0.85. On API failure, render an explicit error with a retry button. Replace `reviewedOn = now` with a real authored date stored in the playbook.

*Acceptance.* `grep -r "HEURISTIC_FALLBACK" frontend/src` returns nothing. Stop the backend, click resolve → UI shows "Resolver unavailable — retry", no result card. Playbook shows a fixed review date.

*Why this is P0.* It is the cheapest fix with the highest integrity payoff, and it takes twenty minutes.

---

**P0-5 — Honest evaluation (fixes B4)**

*Spec.* Two datasets:
- **Tuning set** — the current 20 rows, patterns allowed to exist. Reported separately.
- **Holdout set** — 15–20 descriptors that have never been seen by the resolver, with known ground truth, including at least 4 deliberately ambiguous ones (`PAYU*SVC9981`, `RAZORPAY*SUBSCR`, `NACH DR XYZ LTD`).

Report both numbers on a `/metrics` page or a slide: precision, recall, and label accuracy for each set, plus the ambiguous-subset accuracy. A 78% on holdout is more credible than 100% on tuning, and judges reward the honesty.

*Acceptance.* Metrics page loads from a real endpoint, shows both sets, and states the sample size and the date.

---

**P0-6 — Sarvam integration (the differentiator)**

Full contract in §6. Minimum viable version: one button, one real call, real audio.

*Acceptance.* Clicking "Brief me" on a recurring item produces audible Hinglish speech naming that merchant and its true next-charge date. Server logs show the outbound Sarvam request. Kill the Sarvam key → the button shows a clear error, not silence and not a canned file.

---

**P0-7 — Renewal calendar and reminders (in-app)**

*Spec.* A calendar view of next-charge dates for the next 90 days, grouped by week, with the amount. Reminder = an in-app badge and a dashboard banner ("3 charges in the next 7 days, ₹1,847"). Email/WhatsApp is P1; do not attempt it before 12:00.

*Acceptance.* Every active subscription appears on the calendar exactly once at its next charge date. Banner count matches the list.

---

**P0-8 — Audit trail**

*Spec.* Append-only collection: `{ userId, subscriptionId, action, fromState, toState, actor, at, metadata }`. Every state change writes one row. Surface it as a timeline on the item detail.

*Acceptance.* Request → confirm → revoke produces three ordered rows visible in the UI with timestamps. Rows survive a restart.

*Why it matters.* It converts "we built a tracker" into "we built something an auditor would accept", which is the B2B story.

---

**P0-9 — Deployment**

*Spec.*
- `Dockerfile` for the backend (multi-stage, JRE 25 base, non-root user).
- Backend on Render or Railway with env vars: `MONGODB_URI`, `MANDATESHIELD_JWT_SECRET`, `SARVAM_API_KEY`, `CORS_ALLOWED_ORIGINS`, `SPRING_PROFILES_ACTIVE=prod`.
- Frontend on Vercel with `VITE_API_BASE_URL` pointing at the backend.
- MongoDB Atlas: dedicated database `mandateshield_demo`, network access for the host, least-privilege user.
- `CorsConfig` reads `CORS_ALLOWED_ORIGINS` (comma-separated), defaulting to localhost in dev.

*Acceptance.* Open the Vercel URL on a phone with Wi-Fi off. Register, seed, see data. `curl -I` shows HTTPS and HSTS. No secret appears in any bundle (`grep -r "SARVAM" frontend/dist` returns nothing).

*Fallback.* If hosting fights you past 00:30, ship a `docker compose up` + a recorded video and say so plainly. A working local demo beats a broken URL, but only just.

---

**P0-10 — CI**

*Spec.* `.github/workflows/ci.yml`: backend `mvnw test` on Java 25, frontend `npm ci && npm run lint && npm run build`. Cache Maven and npm. Do not run integration tests that need Atlas in CI.

*Acceptance.* Green check on the release branch. README link is now true.

---

**P0-11 — Demo reset**

*Spec.* `POST /api/v1/demo/reset` (authenticated, and rate-limited) deletes the caller's transactions, subscriptions, audit rows, and generated letters, then reseeds on request. Guard it behind an env flag so it cannot be hit in a real deployment.

*Acceptance.* Reset twice in a row; second run leaves a clean, seeded state with no duplicates.

---

**P1 (only if ahead of schedule)**

- Email reminder via a transactional provider, one template, one cron.
- Pagination on `/transactions`.
- Account deletion + JSON export (DPDP-friendly).
- Password reset.
- Sarvam STT: voice query instead of typed.
- Basic rate limiting (bucket per user per minute).

**P2 (do not start before 12:00)**

- Account Aggregator sandbox.
- Real cancellation execution.
- Family accounts.
- Neo4j merchant graph.

### 4.4 Cut list

Say these out loud in the pitch as "deliberately out of scope", which reads as judgement rather than incompleteness:

- Automatic cancellation execution.
- Bank connectivity.
- Email/WhatsApp reminders.
- Native mobile apps.
- Multi-user households.
- Non-INR billing.

---

### 4.5 Second-pass gap analysis — what still separates this from a winning build

This list came from reading the import path, the DTOs, the indexes, the controllers and the CSV parser line by line, plus asking one question of every screen: *what does a sceptical judge do here, and what breaks?*

Items marked **↺** were already in §3.2. Everything else is new. The **Time** column is realistic solo-developer time including a manual test, not "it compiles" time. The **P** column is priority: **P0** = fixes something a judge will hit; **P1** = fixes something a judge will notice; **P2** = polish.

#### A. Live-demo killers

These are the ones that fail *during* the pitch, not in review.

| # | Flaw and evidence | Why it costs you | Easy fix | Time | P |
|---|---|---|---|---|---|
| **A1** | **Merchant identity is case-sensitive, so one brand becomes two subscriptions.** `TransactionController.create` uppercases a supplied `merchantName` (`NETFLIX`), while the resolver returns title case (`Netflix`). The pipeline groups by `resolvedMerchantName` with an exact `groupingBy`, and looks up with `findByUserIdAndMerchantName` (exact match). The shipped CSV template includes a `merchant_name` column, so a normal import mixes both forms. | Netflix appears twice, annual spend is double-counted, and the "savings" number is wrong on screen. This is the worst possible failure: it looks like a maths bug in the core product. | Canonicalise at write time: store `merchantKey = brand.toUpperCase().replaceAll("[^A-Z0-9]", "")` alongside the display name; group and look up by `merchantKey` only. Add a one-off merge for existing duplicates. | 1h | **P0** |
| **A2** | **Import is non-atomic and non-idempotent.** `importTransactions` streams each row through `create()`, which saves immediately. Row 10 failing returns 400 with rows 1–9 already committed. Retrying the same file duplicates them. No content hash, no dedupe — even though `findByUserIdAndRawDescriptor` already exists. | Judge imports a CSV, one row is malformed, they fix it and re-import, and the dashboard silently doubles. Unrecoverable in a 7-minute demo without a reset. | Two passes: validate every row first (descriptor, positive amount, parsable date, 3-letter currency) and return `{row, reason}` for all failures before writing anything. Then write. Add `importHash = sha256(userId + descriptor + amount + date)` and skip hashes already present via one `findByUserIdAndImportHashIn` query. | 1.5h | **P0** |
| **A3** | **Real bank CSVs will not import.** `parseCsv` is hand-rolled and `TransactionController.parseDate` accepts only `Instant.parse` or `yyyy-MM-dd`. Indian bank exports are almost always `DD/MM/YYYY`. Excel's UTF-8 export prepends a BOM, so the first header becomes `\ufeffdescription` and every row is filtered out. Rows missing a field are dropped by `.filter(...)` while the toast still reports the surviving count. | "Bring your own statement" is the strongest possible demo beat, and right now it fails on the two most common real-world formats — with an error message that blames the user. | Strip the BOM (`text.replace(/^\uFEFF/, '')`). Add a `normalizeDate()` accepting `YYYY-MM-DD`, `DD/MM/YYYY`, `DD-MM-YYYY`, `DD MMM YYYY`, and Excel serial numbers. Return and display `{imported, skipped: [{row, reason}]}` instead of silently dropping. | 45m | **P0** |
| **A4** | **Bulk import is O(n²).** Every `create()` calls `synchronizeKnownTierOne`, which runs `findByUserIdAndRawDescriptor` and saves every match. For 500 rows of a repeated descriptor that is up to 250,000 writes, synchronously, inside one request. | A 200-row import times out or takes 40 seconds on stage. The judge assumes the backend is broken, not busy. | Add a `bulk` flag to the import path that skips per-row synchronisation. Collect the distinct descriptors, run **one** sync pass after the batch commits. | 1h | **P0** |
| **A5** | **Date drift around midnight IST.** `TransactionView.from` renders `atZone(ZoneOffset.UTC).toLocalDate()`. A charge at 00:30 IST is stored as 19:00Z the previous day and displays one day early. Interval maths uses the same instants, so a 30-day cycle can read as 29 or 31. | "Next charge 28 Nov" when the user knows it is the 29th destroys credibility in one sentence. | A billing date is a calendar date, not an instant. Store `LocalDate` for `transactionDate`, or convert with `ZoneId.of("Asia/Kolkata")` at both the display and interval boundaries. | 45m | **P1** |
| **A6** | **"Savings" and "waste" are the same number, and the total is silently capped.** `ExecutiveDashboardService` passes `annualizedWaste` twice into `totalAnnualizedWaste` and `potentialAnnualSavings`, then clamps it with `.min(estimatedAnnualSpend)`. | A judge who subtracts the two numbers on the slide finds zero. A judge who adds up the anomaly cards finds they exceed the headline. Both look like you do not understand your own model. | Define them separately: **waste** = impact of anomalies on untouched items; **savings** = impact on items the user acted on (requested or confirmed). Remove the clamp, or show "capped at total spend" explicitly. | 30m | **P1** |

#### B. Data integrity and scale

| # | Flaw and evidence | Why it costs you | Easy fix | Time | P |
|---|---|---|---|---|---|
| **B1** | **No index on `raw_transactions`.** `SubscriptionDocument` correctly declares `(userId,status)` and `(userId,merchantName)` compound indexes; `RawTransactionDocument` declares none. Every list, every pipeline run, every dashboard load is a collection scan. | "How does this scale?" is a standard question, and the honest answer today is "it doesn't". It is also the cheapest possible thing to fix. | `@CompoundIndex(name="user_date_idx", def="{'userId':1,'transactionDate':-1}")` plus `{'userId':1,'synthetic':1}`. Mongo builds them on boot. | 10m | **P1** |
| **B2** | **`GET /transactions` is unbounded.** `findByUserIdOrderByTransactionDateDesc` returns the full history with no page, size, filter or search. | Two years of statement is a multi-megabyte JSON payload on a phone. The judge sees a spinner and assumes a hang. | `Pageable` with `?page&size` (default 50) and optional `merchant`, `from`, `to`. Frontend keeps its current shape by requesting size=500 for now. | 45m | **P1** |
| **B3** | **The pipeline rewrites every transaction on every run.** Line 78 of `MandateShieldPipelineService` calls `save(tx)` unconditionally inside the loop, even when nothing changed. `report-unused` triggers a full run. | Marking one item "unused" causes N writes and a visible pause. On Atlas free tier this is the difference between snappy and sluggish. | Track a `dirty` flag per row (compare the five resolved fields before and after) and only save changed documents. | 30m | **P2** |
| **B4** | **Registration is O(n).** `UserSequenceService.nextUserId()` calls `userRepository.findAll()` and streams for the max ID on **every signup**. | Slow signup is the first thing a judge experiences. At scale it is a guaranteed outage. | Delete the `findAll()`. Use a single `findAndModify` with `upsert:true` and `$inc`; initialise the counter once from `countDocuments()` or a fixed base. | 15m | **P1** |
| **B5** | **Duplicate signup can return 500.** `register` does `existsByEmailIgnoreCase` then `save` — a TOCTOU race. The unique index catches it, but the `DuplicateKeyException` falls through to the generic 500 handler. | A judge double-clicks "Create account" and gets "An unexpected error occurred" instead of "this email is taken". | Wrap `users.save(user)` in `try/catch (DuplicateKeyException)` and rethrow as 409 with the existing message. | 10m | **P1** |
| **B6** | **Dashboard scans all transactions to find trials.** `getDashboard` calls `findByUserId(userId)` and filters in memory for trials ending in the next 48 hours. | Dashboard latency grows with history size, for a list that is almost always empty. | Derived query: `findByUserIdAndTrialTransactionTrueAndTrialEndDateBetween(userId, now, now+48h)`. | 20m | **P2** |

#### C. Product gaps that read as "prototype"

| # | Flaw and evidence | Why it costs you | Easy fix | Time | P |
|---|---|---|---|---|---|
| **C1** | **There is no next-charge date anywhere.** Neither `SubscriptionDocument` nor `SubscriptionDto` carries it. The dashboard can say "you spend ₹14,244 a year" but cannot say "₹649 leaves on the 28th". | The single most actionable fact in the product is missing. "So what do I do *now*?" has no answer on screen. | Compute `nextChargeDate = lastPaymentDate + period` in `RecurringDiscoveryEngine`, add it to the DTO, sort the dashboard by it, and show a "next 7 days" count. | 1h | **P0** |
| **C2** | **Cancellation guidance is generic for everything except Netflix.** `getPlaybookDetails` special-cases one merchant, then falls back to rail-level text. For Cult.fit, Swiggy One or LinkedIn, the "walk me to the exit" promise becomes "check your app". | The second half of the value proposition is the weakest part of the demo, and it is the part you ask a judge to believe. | Add `cancelUrl` and `cancelPath` to `MerchantPatternDocument` and populate the top 25 Indian-consumer brands (Netflix, Spotify, Hotstar, Prime, Zee5, SonyLIV, JioCinema, YouTube Premium, Google One, iCloud, Cult.fit, Swiggy One, Zomato Gold, LinkedIn, Adobe, Canva, ChatGPT, Notion, Audible, Kindle, Microsoft 365, Dropbox, Times Prime, Gaana, Airtel Xstream). Surface as an "Open cancel page" button. **This is data entry, not engineering — highest value per hour on the list.** | 2h | **P0** |
| **C3** | **Generated letters are never stored.** `generateLetter` returns `Map.of("letter", letter)` and forgets it. No reference number, no history, no download. | "We track your cancellation" is only true for the status flag, not the document you sent. A judge asking "where is the letter?" finds nothing. | New `letters` collection: `{userId, subscriptionId, body, refNo, createdAt}`. Return the `refNo`, add "Copy" and "Download" (browser print-to-PDF is enough). | 1h | **P1** |
| **C4** | **Uncertainty has no resolution path.** The dashboard surfaces `tentativeSubscriptions`, but there is no "yes, this is mine", "not mine", or "dismiss" action, and `SubscriptionStatus` has no `DISMISSED`. A false positive is permanent. | The resolver *will* produce false positives. Showing them with no way to correct them makes the model feel unaccountable — the opposite of the "believe it" pillar. | Add `DISMISSED` to the enum, `POST /subscriptions/{id}/confirm` and `/dismiss`, and two buttons on each tentative card. Dismissed items are excluded from spend and from future runs. | 1.5h | **P0** |
| **C5** | **No search, filter or sort.** Twenty rows are fine; a judge importing their own statement gets a wall. | The moment the dataset becomes real, the UI stops being usable. | Client-side only for ≤500 rows: a search box, category chips, and an "only anomalies" toggle. No backend work. | 45m | **P1** |
| **C6** | **No deep links.** `activeTab` is React state; refresh resets to the dashboard, and you cannot send a judge a link to a specific item. | In Q&A you cannot say "look at the third item" without driving. Also breaks the share loop. | Hash routing: `#/dashboard`, `#/item/{id}`. About 20 lines, no router dependency. | 30m | **P1** |
| **C7** | **The first screen is a login form.** A judge opening the URL sees a password field, not a product. | The first five seconds decide the frame for the next five minutes. A login wall says "unfinished side project". | Public landing route with the one-liner, three real screenshots, and **"Open the live demo"** that signs into a shared demo account in one click and resets it on entry. | 1.5h | **P0** |
| **C8** | **No error boundary, skeletons, or empty states.** A render error is a white screen; loading is a layout jump; the transactions page with no data is blank. | White screens are unrecoverable on stage. Blank pages look broken rather than empty. | 15-line React error boundary with a Reload button; three grey skeleton cards per panel; one sentence plus one primary button per empty state. | 1h | **P1** |
| **C9** | **`/request` and `/revoke` are the same operation, and transitions are unguarded.** Both set `CANCELLATION_REQUESTED`. You can call `/confirm` without `/request`, or re-request a cancelled item back to "requested". | A judge reading the API surface sees two names for one behaviour and no state machine — the opposite of the audit-trail story. | A tiny transition table (`ACTIVE → CANCELLATION_REQUESTED → CANCELLED`, plus `→ DISMISSED`) and a 409 on illegal moves. Delete or rename `/revoke`. | 45m | **P1** |

#### D. Trust, security and honesty

| # | Flaw and evidence | Why it costs you | Easy fix | Time | P |
|---|---|---|---|---|---|
| **D1** | **JWT lives in `localStorage`.** `frontend/src/api.js` reads `mandateshield_token` from storage. Any XSS is a full account takeover. | Security-literate judges know this instantly. It is also the standard hackathon shortcut, so a confident, honest answer is enough. | Do not migrate tonight. Shorten expiry to 8h, and answer: "localStorage is the known tradeoff for a stateless demo; the production path is an httpOnly, SameSite cookie with CSRF protection, which we did not have time to do safely." | 30m | **P1** |
| **D2** | **No rate limiting anywhere.** Login, register and resolve are all unthrottled. | "What stops me brute-forcing this?" has no answer. | In-memory `ConcurrentHashMap<String, Counter>` keyed by IP+route, 10 attempts per 15 minutes, reset on success. ~40 lines, no dependency. | 45m | **P1** |
| **D3** | **Password policy is length-only.** `@Size(min=8)` accepts `password` and `12345678`. | Trivial to spot in a demo, and a fintech judge will try it. | Reject a 100-entry common-password list and require one non-letter. | 20m | **P2** |
| **D4** | **No account deletion or export.** | Under India's DPDP Act this is a real obligation, and a bank partner will ask in the first meeting. | `DELETE /auth/me` (cascade by `userId`) and `GET /auth/export` returning JSON. Both are small and both are strong trust signals. | 1h | **P1** |
| **D5** | **Error shape contradicts the README.** README line 223 says errors follow Spring's Problem Detail format; `GlobalExceptionHandler` returns a custom `{error, message, timestamp}` map. | A judge who reads the README and then curls the API catches you in a documentation lie — for no benefit. | Fix the README now (1 minute). Migrating to `ProblemDetail` is a P2. | 10m | **P2** |
| **D6** | **Validation errors lose the field name, and the handler can NPE.** `handleValidation` always returns "Required fields must not be blank." regardless of the actual violation, and `Map.of("message", e.getMessage())` throws if a message is null (e.g. `NoSuchElementException` from `orElseThrow()`). | Poking the form is the cheapest thing a judge can do. Getting a vague message — or an empty 500 — from an invalid email looks careless. | Map `getBindingResult().getFieldErrors()` into `"email: must be a valid email"`, and use `String.valueOf(e.getMessage())`. | 20m | **P1** |

#### E. Presentation and narrative

| # | Flaw and evidence | Why it costs you | Easy fix | Time | P |
|---|---|---|---|---|---|
| **E1** | **No favicon, page title, or OG image.** | The submission link renders as a blank grey card in the judging portal and in every WhatsApp share. | One 1200×630 PNG, one `<title>`, three `<meta>` tags. | 30m | **P1** |
| **E2** | **No privacy or consent line at signup.** | For a product that reads financial behaviour, silence reads as naivety. | One paragraph plus one checkbox: what is stored, where, and how to delete it. | 15m | **P1** |
| **E3** | **No analytics.** | You cannot answer "how did the first 100 users behave?" — which is the question behind the whole growth slide. | A `usage_events` collection and five events: `signup`, `import`, `seed`, `brief_played`, `cancel_requested`. | 45m | **P2** |
| **E4** | **No "how it works" explainer for the three tiers.** | The resolver is the most sophisticated part of the build and the least legible. | A three-step strip on the dashboard: descriptor → merchant → decision. | 30m | **P2** |
| **E5** | **README and deck overclaim.** CI that does not exist, ProblemDetail that is not used, "cancels subscriptions" in the deck. | Judges read READMEs. One false claim contaminates every true one. | A 20-minute truth pass over README and slides before submission. Non-negotiable. | 20m | **P0** |
| **E6** | **No shareable artifact.** The referral loop in §8.9 has nothing to send. | A growth plan with no loop is a slide, not a plan. | "Share my savings" card: annual figure, item count, one line. Client-side render to PNG. Optional. | 1.5h | **P2** |
| **E7** | **No team or "what we'd do with the prize" slide.** | Judges score ambition and team credibility, and most decks leave it implicit. | One slide: who built what, and the next 90 days. | 15m | **P1** |

**Total if you did everything: ~26 hours.** You have ~22, and 8 of them are already committed in §4.2. That is why §4.6 exists.

---

### 4.6 The top ten by score-per-hour

Ranked by points moved per hour spent, using the §7 scorecard. Do these in order; stop when the clock says stop.

| Rank | Item | Hours | Scorecard axis | Why it is this high |
|---|---|---|---|---|
| 1 | **C7** — public landing + one-click demo entry | 1.5 | UX, deployment | Removes the login wall. Biggest single perception change available. |
| 2 | **P0-4** — delete fabricated resolver fallback (§4.2) | 0.3 | Reliability, integrity | Twenty minutes to remove the only line of code that can be caught lying. |
| 3 | **A1** — merchant key canonicalisation | 1.0 | Core, backend | Prevents a visible double-count of the headline brand. |
| 4 | **A2** — atomic, idempotent import | 1.5 | Reliability, core | Turns "import your statement" from a trap into a feature. |
| 5 | **C2** — cancel URLs for 25 brands | 2.0 | Core, UX | Data entry that upgrades the weakest half of the value proposition. |
| 6 | **C1** — next-charge date everywhere | 1.0 | Core, UX | Adds the most actionable fact the product is missing. |
| 7 | **A3** — real CSV formats | 0.75 | Core | Unlocks the "bring your own bank file" demo beat. |
| 8 | **C4** — dismiss and confirm | 1.5 | Trust, core | Makes the model accountable; converts false positives from a liability into a conversation. |
| 9 | **B1 + B4** — indexes and O(1) signup | 0.5 | Backend, scale | Cheapest credible answer to "does this scale?" |
| 10 | **E5 + D5** — README truth pass | 0.3 | Honesty | Removes the claims a judge can disprove in 30 seconds. |

**Cumulative: 10.35 hours.** That is the difference between a correct prototype and a winning one, and it fits if you protect the 00:30–06:00 sleep block and cut §4.2 P1.

**Verification for the top five** — each takes under 60 seconds and should be run before every rehearsal:

- **C7:** open the URL in a private window. You see the product, not a form, and one click reaches data.
- **A1:** import one CSV with `merchant_name=Netflix` and one without. Exactly one Netflix subscription exists.
- **A2:** import the same file twice. The second run reports "0 imported, N duplicates" and the dashboard does not move.
- **C2:** open a Cult.fit item. There is a real cancel URL, not "check your app".
- **C1:** every active item shows a next-charge date, and the earliest one matches the "next 7 days" count.

---

### 4.7 Triage — pick a lane and stop

**If you have 3 hours left**

P0-4 (fake fallbacks) → A1 (merchant keys) → A3 (CSV dates and BOM) → E5/D5 (README truth) → B1+B4 (indexes and signup). A static landing page with a demo button is the stretch goal. This lane fixes credibility and the two most likely live failures.

**If you have 8 hours left**

Add C1 (next-charge date) → A2 (atomic import) → C2 for the top 10 brands only → C4 (dismiss/confirm) → D2 (rate limit). This lane makes the product feel finished rather than correct.

**If you have the full window**

Everything in §4.2 P0, then §4.6 in order, then stop. Do not start §4.5 items below rank 10, do not attempt E6, and do not refactor anything that already passes its test.

**Do not do, under any circumstances**

- Do not add a router library or a state-management library. Hash routing and `useState` are enough.
- Do not migrate auth to cookies tonight. A half-migrated auth flow is worse than a documented tradeoff.
- Do not build a graph database, hardware, or an email pipeline.
- Do not chase 100% accuracy on the tuning set. Publish the holdout number instead.
- Do not add a feature after 11:15. Freeze means freeze.

---

## 5. Who we build for

### 5.1 Primary segment — "the ₹4,000-a-month subscriber"

**Name:** Ananya Rao.
**Age / city:** 27, Bengaluru (Indiranagar), originally Hyderabad.
**Job:** Product analyst at a SaaS company. ₹14.5 lakh CTC, roughly ₹1.02 lakh in hand per month.
**Living situation:** Rents a 1BHK with one roommate. No dependants. Parents partly supported.
**Devices:** iPhone 13, MacBook Air (work), a Windows laptop she barely uses. UPI is her default payment rail; one credit card for larger purchases.
**Subscriptions she can name:** Netflix, Spotify, Amazon Prime, Google One, iCloud, Cult.fit, a gym app she forgot, ChatGPT Plus, Notion, Swiggy One, Zomato Gold, Hotstar (via a friend's family plan), LinkedIn Premium (trial she meant to cancel), two newsletters on Substack.
**Subscriptions she cannot name:** a ₹99 "premium" inside a fitness app, an annual domain renewal, a cloud storage plan from an old phone, a ₹149 astrology app from a bad week in 2024.
**Monthly outflow:** ₹3,800–₹4,600, and she would guess ₹1,800 if asked.
**Trigger to act:** the card statement arrives, or a "payment failed" SMS fires because the card expired. Both are reactive. By then she has paid for two extra months.
**Current workaround:** scrolls the bank app's UPI AutoPay section, screenshots it, forgets. Has never successfully cancelled anything that wasn't in-app.
**What she says:** *"I know I'm wasting money. I just don't know which ones, and cancelling means finding the right page in the right app and hoping it worked."*
**Willingness to pay:** ₹99/month is an easy yes if it saves ₹500+ in the first month. ₹199 is a no.
**Why she is the beachhead:** high subscription density, high digital literacy, high pain, low tolerance for a bank-only solution, and she is reachable through the exact channels in §8.9.

**Job to be done:** *When my statement arrives and I feel the leak but can't see it, I want to know exactly which recurring charges are mine, which are waste, and how to stop each one, so I stop paying for things I forgot I had.*

### 5.2 Segment 2 — "the freelancer with business subscriptions"

**Name:** Karthik Menon. 31, Kochi. Independent designer, 3–5 clients at a time. Income ₹80k–₹2.2L/month, lumpy.
**Subscriptions:** Adobe CC, Figma, Framer, Notion, Loom, Vercel, two domain registrars, three stock-asset libraries, a project-management tool, a font licence, a podcast-hosting plan, plus personal Netflix/Spotify.
**Pain:** he needs these for work, so "cancel everything" advice is useless. He needs *which of these did I use this month* and *which annual renewals are about to hit in a low-income month*. He also needs the spend to be categorisable for his CA.
**Why it matters commercially:** higher ARPU, more subscriptions, annual plans with big ticket sizes, and a natural upgrade path to a "business" tier at ₹299/month. Also the segment most likely to pay for export and audit trail.
**Product implication:** usage signals and annual-renewal forecasting matter more than "cancel". Do not design the whole product around cancellation.

### 5.3 Segment 3 — "the household CFO"

**Name:** Priya and Arjun Sharma. 36 and 38, Pune. Two kids (6 and 11). Combined income ₹26L.
**Subscriptions:** kids' learning apps (two, one abandoned), three OTT services (one via a grandparent's account), a family music plan, cloud storage across three providers, an annual antivirus, a gym for two, a newspaper digital edition, a car-connected app, plus school portals.
**Pain:** nobody knows the total. Each parent pays for overlapping services. Annual renewals arrive as surprises. They want a shared view and a "who is paying for what" answer, not a cancellation tool.
**Why it matters:** this is the segment that makes the B2B2C story work. A bank or a fintech can offer this as a household feature. It is also where a family plan at ₹149/month is easy to justify.
**Product implication:** multi-member view, per-member attribution, and annual-renewal forecasting. Today's single-user model does not support it — say "designed for", not "supports".

### 5.4 Segment 4 (optional, B2B2C) — the distribution channel

Not a user segment so much as a buyer: **a mid-size Indian bank or neobank with a UPI AutoPay book**. Their problem is mandate dormancy and failed-mandate support cost. Our product is a white-label "review your recurring payments" surface that reduces support tickets and gives them a reason to talk to the customer before a debit fails.

Buyer: Head of Digital Products or Head of Cards. Budget exists. Sales cycle is long — which is exactly why the hackathon needs the B2C story to stand alone.

### 5.5 Segment comparison

| | Ananya (primary) | Karthik | Sharma household | Bank (B2B2C) |
|---|---|---|---|---|
| Subscriptions | 12–15 | 15–20 | 10–14 | n/a |
| Monthly spend | ₹4k | ₹9k | ₹6k | n/a |
| Core job | Find + stop waste | Forecast + justify | See + share | Reduce support cost |
| Killer feature | Evidence + guided cancel | Annual renewal calendar | Shared view | White-label + analytics |
| Willingness to pay | ₹99/mo | ₹299/mo | ₹149/mo | ₹12/active user/mo |
| Reachable via | Creators, Reddit, X | Designer communities | School/parent groups | Direct sales |
| In this build? | **Yes, fully** | Partially (calendar) | No | No |

**Pitch rule:** lead with Ananya. Mention Karthik and Priya as expansion, in one sentence each. Never present four segments as four products.

---

## 6. Partner technology: Sarvam AI

### 6.1 Decision

**We build on Sarvam AI.** It is the only partner option that changes what the product *is* rather than how it is stored or what it blinks.

Three reasons, in order of weight:

1. **It addresses a real adoption barrier.** The target user reads English fine but thinks about money in Hinglish. A renewal briefing she can *hear* while getting ready — "Ananya, kal Cult.fit ka ₹1,499 auto-debit hoga, aur woh pichhle 40 din se use nahi hua" — is a different product from a table of rows. Translation and TTS are not decoration here; they are the interface for the segment that banks underserve.
2. **It solves a genuine technical problem in our domain.** Indian bank descriptors are transliterated, abbreviated, and inconsistent (`NACH DR HDFC LIFE`, `PAYU*SVC9981`, `RAZORPAY*SPOTIFYIN`). A model that handles Indic transliteration variants and code-mixed strings is doing real work inside the resolver, not sitting beside it.
3. **It is demoable in fifteen seconds.** Judges remember what they hear. They do not remember a schema diagram.

### 6.2 Why not Neo4j

Neo4j is the "sophisticated" choice and it is the wrong one *for this build*.

- **We do not have a graph problem yet.** The interesting relationship — descriptor → merchant → subscription → mandate → user — is a shallow tree of depth 3 with at most a few hundred nodes per user. MongoDB handles that with two indexes. A graph database earns its place at millions of edges with multi-hop traversals, which is a *phase-two* problem.
- **It adds a second datastore to operate, back up, secure, and deploy** in a 22-hour window, in exchange for query elegance nobody will see on stage.
- **The demo gain is a diagram, not a moment.** "We use a graph database" is a slide. A judge cannot see it.
- **Honest position if asked:** "Neo4j is the right answer for the merchant-knowledge graph at scale — shared patterns across tenants, alias clustering, fraud rings. We deliberately deferred it; here is the migration path." That answer is stronger than a half-built graph.

If you want the graph *story* without the operational cost, draw the entity graph in the deck and say it is modelled, not stored, in Neo4j.

### 6.3 Why not Arduino

Arduino is a hardware answer to a software problem.

- There is no sensor in this product. Nothing is measured physically.
- The "physical alert" idea (a desk device that lights up before a renewal) is charming and completely unvalidated. It also adds a supply chain, a pairing flow, and a failure mode to a 7-minute demo.
- A judge will ask "why does this need hardware?" and there is no good answer.
- If you want a physical moment, a ₹0 hardware alternative exists: a phone notification. Ship that.

**Honest position if asked:** "We considered a physical renewal alert. It failed the 'does this need to exist' test, and it would have cost us the deployment block."

### 6.4 What we actually build with Sarvam

Four surfaces, ordered by demo value per hour of work.

**S1 — Renewal briefing (chat + TTS). The hero.**
User opens a recurring item (or the dashboard) and presses **"Brief me"**. Backend assembles a compact context from MongoDB — merchant, amount, cadence, next charge date, days until charge, anomaly flags, whether it is used — sends it to Sarvam chat with a strict JSON-output instruction, then sends the resulting one-paragraph Hinglish script to Sarvam TTS and returns base64 audio.

Prompt contract (must be enforced, not hoped for):
- Output language: Hinglish, Roman script.
- Max 2 sentences, max 220 characters.
- Must include the real amount and the real next-charge date from the context.
- Must not invent a merchant, an amount, or a date.
- If a field is null, omit the sentence rather than guessing.

Validate the response: if the returned amount or date does not appear in the context, discard and use a deterministic template fallback (still real data, just not generated). That keeps the honesty guarantee.

**S2 — Descriptor understanding (translate + chat). The technical credibility.**
For Tier-3 descriptors, ask Sarvam to normalise the string: expand abbreviations, transliterate Devanagari/Tamil/Telugu fragments to Latin, and propose a canonical merchant name plus category. This runs *before* the fuzzy matcher and improves it. Log the proposal and the final decision separately so you can show the model's contribution.

**S3 — Voice query (STT). The delight.**
"Kitna kharch ho raha hai subscriptions pe?" → STT → intent extraction → a deterministic query against MongoDB → answer. Keep the intent set to five: total spend, next charge, most expensive, unused count, cancel help. Anything outside the set returns "I can answer these five things" rather than a hallucination.

**S4 — Vernacular UI copy (translate). The polish, if time allows.**
Translate the six highest-value UI strings into Hindi and one South Indian language. Do not machine-translate the whole app; half-translated UI looks worse than English-only.

### 6.5 Integration contract

Base URL: `https://api.sarvam.ai`
Auth header: `api-subscription-key: ${SARVAM_API_KEY}`
All keys server-side only. The frontend never sees the key; it calls our backend, which proxies.

| Purpose | Endpoint | Model / params | Notes |
|---|---|---|---|
| Chat / briefing | `POST /v1/chat/completions` | `sarvam-105b` (pin the exact ID in config; the catalogue also lists `deepseekv4.1-flash-1017` — pick one and do not switch mid-demo) | `temperature: 0.3`, `max_tokens: 220`, JSON-mode style instruction |
| Translation | `POST /translate` | `source_language_code: en-IN`, `target_language_code: hi-IN`, `mode: formal` | For descriptor normalisation use `mayura:v1` style transliteration if available; otherwise translate-then-match |
| Text to speech | `POST /text-to-speech` | `target_language_code: hi-IN`, a female speaker, `speech_sample_rate: 22050` | Returns base64 audio; cache per (subscriptionId, nextChargeDate, amount) |
| Speech to text | `POST /speech-to-text` | `language_code: hi-IN` or auto | 15-second clips; cap upload size |

Request shape (briefing):

```json
{
  "model": "sarvam-105b",
  "temperature": 0.3,
  "max_tokens": 220,
  "messages": [
    { "role": "system", "content": "You write one short Hinglish (Roman script) renewal briefing. Use ONLY the facts in the user message. Never invent amounts or dates. Max 2 sentences, max 220 characters." },
    { "role": "user", "content": "merchant=Netflix; amount_inr=649; cadence=monthly; next_charge=2026-11-28; days_until=7; price_hike=true; old_amount_inr=499" }
  ]
}
```

Expected: `"Netflix ka price ₹499 se badhkar ₹649 ho gaya hai, aur agla debit 28 November ko hoga."`

Env vars: `SARVAM_API_KEY`, `SARVAM_CHAT_MODEL`, `SARVAM_TTS_SPEAKER`, `SARVAM_ENABLED=true|false`.

### 6.6 Cost model

Published rates at time of writing — **re-verify before the pitch**, pricing pages change.

| Item | Rate |
|---|---|
| Chat input | ₹10 / 1M tokens |
| Chat output | ₹25 / 1M tokens |
| Translation | ₹2 / 1,000 characters |
| TTS | ₹3 / 1,000 characters |
| STT | ₹0.49 / minute |
| Free credits | ₹100 on signup |

**Worked monthly cost for one Pro user** (6 recurring items, 2 voice briefings/week, 2 voice queries/week, 6 descriptors translated twice):

| Component | Volume | Cost |
|---|---|---|
| Chat | 8.6 queries × (1.5k in + 0.3k out) | ₹0.19 |
| Translate | 480 chars | ₹0.96 |
| TTS (cached, 2 generations) | 1,200 chars | ₹3.60 |
| STT | 2.15 min | ₹1.05 |
| **Total** | | **≈ ₹5.80** |

That is **~6% of a ₹99 ARPU** — healthy. Without TTS caching the same usage costs ~₹17.7 (18% of ARPU), which is why the cache key matters.

The ₹100 free credit covers roughly **17 user-months** at this rate: enough for the entire hackathon and a week of demos.

### 6.7 Failure policy (non-negotiable)

- Sarvam timeout or 5xx → the button shows "Voice briefing unavailable" and offers the text briefing (deterministic template, real data).
- Invalid or missing API key → the feature is hidden behind a flag, not silently degraded.
- Model output fails validation (invented amount/date) → discard, use template, log the rejection.
- **Never** ship a pre-recorded audio file as if it were generated. If a judge asks "is that live?", the answer must be yes, and you must be able to prove it by changing the amount in the DB and regenerating.

### 6.8 The demo moment

Change the Netflix amount in the database from ₹649 to ₹799, press "Brief me" again, and let the judge hear the new number. That single beat proves the whole stack is real — database, model, speech — in eight seconds. Rehearse it.

---

## 7. Judging criteria scorecard

Scores are out of 10 and reflect *today*, not ambition. The target column is what 12:00 PM should look like.

### 7.1 Core functionality

**Today: 6.5.** The happy path genuinely works: ingest → resolve → discover → anomaly → dashboard → guidance. The failures are at the edges: deletion leaves zombies, the playground mutates data, and the cancellation flow guides rather than acts.
**Target: 8.5.** All P0 fixes in, twelve-point DoD passing.
**What judges actually test:** they delete something, they enter garbage, they refresh mid-flow, they click the thing that looks like a button. Make those four boring.

### 7.2 Backend and data

**Today: 7.5.** Real Spring service layer, real repositories, real indexes, three separable engines, sensible domain model, seeded and unit-tested. Deductions: no currency field, no audit collection, no pagination, no reconciliation, evaluation is circular.
**Target: 9.** Currency + audit + reconciliation + holdout evaluation.
**Strongest talking point:** the engines are independently testable and the resolution tiers are explainable. Show the tier, the matched pattern, and the confidence — most competitors show a black box.

### 7.3 Auth and security

**Today: 7.** JWT with no source fallback, BCrypt-hashed passwords, per-user scoping verified with a 404 (correct: it does not leak existence), consistent error shape.
**Gaps:** no rate limiting, no password reset, no email verification, no MFA, CORS hardcoded, no account deletion, no token refresh, 24-hour token with no revocation list.
**Target: 8.** Rate limiting + env-driven CORS + account deletion. Say plainly that MFA and reset are post-hackathon.
**Q&A trap:** "What stops me brute-forcing login?" Today: nothing. Have the answer ready and ideally ship a simple bucket limiter.

### 7.4 Reliability and error handling

**Today: 5.** This is the weakest axis, because the frontend *lies* on failure. Backend error handling is decent (ProblemDetail, validation, 404 scoping); the client undermines it.
**Target: 8.** Remove fabricated fallbacks, add retry affordances, add timeouts, add a health-backed status banner, make every empty state explicit.
**Cheapest 90 minutes in the whole plan:** deleting the fake fallback and adding three error toasts.

### 7.5 UX and first-time usability

**Today: 6.** Clean dashboard, clear hierarchy, real data density. Problems: no onboarding, no empty-state guidance, sample/live toggle is inconsistent, no deep links, no mobile pass, and the resolver playground's purpose is not obvious to a first-timer.
**Target: 8.5.** A first-time user should reach their first insight in under 60 seconds without instructions. Add a three-step empty state: "1. Load demo data or import CSV → 2. We'll find your recurring payments → 3. Review and act." Add a persistent "what am I looking at" line on the dashboard.
**Test:** hand the laptop to someone who has not seen the app. If they ask "what do I click?", fix that, not the font.

### 7.6 Deployment and live URL

**Today: 1.** Nothing. No container, no host, no CI, no public URL.
**Target: 9.** HTTPS URL, env-driven config, seeded demo account, reset button, backup video.
**This is the highest-leverage block in the plan.** A deployed app with a 7/10 feature set beats a local app with a 9/10 feature set, every time, in every rubric that mentions deployment.

### 7.7 Scorecard

| Criterion | Weight (typical) | Today | Target 12:00 | The one thing that moves it |
|---|---|---|---|---|
| Core functionality | 25% | 6.5 | 8.5 | P0-1, P0-2 |
| Backend / data | 20% | 7.5 | 9.0 | Currency + audit + reconciliation |
| Auth / security | 10% | 7.0 | 8.0 | Rate limit + CORS env |
| Reliability | 10% | 5.0 | 8.0 | Delete fake fallbacks |
| UX | 15% | 6.0 | 8.5 | Empty states + 60-second first insight |
| Deployment | 15% | 1.0 | 9.0 | Dockerfile + Render + Vercel |
| **Weighted** | | **5.6** | **8.6** | |

The jump from 5.6 to 8.6 is almost entirely deployment plus deleting lies. Neither requires new product thinking.

**Caveat on the target column:** it assumes the §4.6 top ten ship. Without them, cap reliability at 6.5 (the import path still fails on real CSVs) and UX at 7.0 (the first screen is still a login form). The scorecard is a promise, not a forecast.

---

## 8. Pitch parameters

### 8.1 Named target customer

**Ananya Rao, 27, product analyst, Indiranagar, Bengaluru.** Twelve to fifteen subscriptions, ₹4,000–₹4,600 a month, can name about seven of them. UPI-first, one credit card, iPhone. She is not disorganised; she is *outnumbered* by auto-debits designed to be invisible.

Say her name on stage. "Users aged 25–35 in metros" is not a customer; Ananya is.

### 8.2 Problem validation evidence

| Evidence | Number | Source |
|---|---|---|
| India OTT users | 665M users, 172.6M active paid subscriptions, +16% YoY | Ormax Media, 2026 — `adgully.com/post/20257` |
| UPI AutoPay scale | ~175M AutoPay transactions in Jan 2025, roughly 3× YoY | Business Standard citing NPCI — `business-standard.com/.../upi-autopay-growth-2025-overtakes-cards-payments-npci-125022000316_1.html` |
| Unused subscriptions, India | Indians among the most likely globally to hold 2+ unused subscriptions; ~23% report two | YouGov — `yougov.com/articles/50030` |
| Paying for unused | 42% of subscribers still pay for at least one unused service | C+R Research — `crresearch.com/blog/subscription-service-statistics-and-costs/` |
| Perception gap | Consumers estimate $86/month, actual spend $219/month (2.5×) | C+R Research |
| India subscription/billing software market | USD 286.23M (2025) → USD 723.22M (2034) | IMARC — `imarcgroup.com/india-subscription-billing-management-market` |

**The argument in one line:** 172.6 million paid OTT subscriptions and 175 million AutoPay debits a month, in a market where 42% of subscribers admit paying for something unused and people underestimate their spend by 2.5×. The leak is structural, not personal.

**Do not** present a survey you did not run as primary research. If you want primary evidence, run a 10-person WhatsApp survey tonight and report n=10 honestly — that is more credible than a borrowed statistic presented as your own.

### 8.3 Current alternative

Ranked by what Ananya actually does today:

1. **The bank app's UPI AutoPay / standing-instruction screen.** Lists mandates, not merchants. `NACH DR HDFC LIFE` is not an answer. No spend total, no cadence, no next date, no cancellation path.
2. **The credit-card statement.** Accurate, monthly, unreadable. Requires manual pattern-matching across 40 lines.
3. **In-app subscription pages** (Apple, Google, Netflix, Spotify). Each shows only its own silo. Finding all of them is a scavenger hunt.
4. **Manual spreadsheet.** Works, abandoned by week three.
5. **Doing nothing.** The modal behaviour, and the one we are competing with.

**The gap in one line:** the data exists in three places, none of which answer "what am I paying for, and what should I stop?"

### 8.4 Value proposition

> **MandateShield turns a bank statement into a list of decisions.** It reads the messy descriptors your bank gives you, tells you which recurring charges are real, what they cost you per year, which ones changed price or converted from a trial, and walks you to the exit for each one — in Hinglish, by voice, with the evidence attached.

Three-part structure for the slide:

- **See it.** Resolve `RAZORPAY*SPOTIFYIN` to Spotify, group it, annualise it. ₹14,244 a year, not "some subscriptions".
- **Believe it.** Every item carries its evidence: the raw descriptors, the dates, the amounts, the confidence tier. No black box.
- **Act on it.** A rail-specific playbook, a ready notice, and a tracked state — so "I cancelled it" becomes a fact with a timestamp, not a hope.

**What we explicitly do not claim:** we do not cancel on your behalf. We remove the two hardest parts — knowing, and knowing what to do — and we track the outcome. Automatic execution is a partnership problem (bank/merchant APIs), not a hackathon problem, and pretending otherwise is how products lose trust on day one.

### 8.5 Business model and pricing

**B2C freemium.**

| Tier | Price | Includes |
|---|---|---|
| Free | ₹0 | 1 dataset, up to 5 recurring items, dashboard, evidence view |
| **Pro** | **₹99/month or ₹799/year** (₹66.6/month effective, 33% off) | Unlimited items, renewal calendar + reminders, voice briefing, cancellation letters, audit trail, CSV export |
| Family | ₹149/month | Pro for up to 4 members, shared household view, per-member attribution |

₹99 is chosen deliberately: below the "do I need to think about this" threshold, above the "this is a toy" threshold, and roughly the price of one subscription it will find. The pitch line: **"It pays for itself the first time it finds one thing you forgot."**

**B2B2C white-label.**

| Tier | Price | Buyer |
|---|---|---|
| Partner | **₹12 per active user per month**, ₹5,00,000 annual minimum | Banks, neobanks, card issuers, PFM apps |
| Enterprise | Custom | Aggregators, payroll platforms |

Why ₹12: it is below the support cost of a single failed-mandate call, which is the buyer's actual comparison. Price against their cost, not against our B2C price.

**Unit economics (target, not measured):**

| Metric | Target |
|---|---|
| Blended ARPU | ₹92/month |
| Gross margin | ~85% (Sarvam ~₹6, infra ~₹8) |
| CAC (creator/referral led) | ₹150–₹250 |
| Payback | 2–3 months |
| Free → Pro conversion | 4–6% |
| Monthly churn | <5% |

Label these as targets on the slide. Judges respect a labelled assumption far more than a fabricated actual.

### 8.6 Market opportunity

**Top-down (software market):** India subscription and billing management: **USD 286.23M in 2025 → USD 723.22M by 2034** (IMARC). This is the B2B/platform slice and it validates that businesses pay for this capability.

**Bottom-up (consumer):**
- 172.6M active paid OTT subscriptions (Ormax 2026).
- At ~3.8 paid subscriptions per paying household, that implies **~45M subscribing households**.
- At ₹99/month × 12 = ₹1,188/year: 45M × ₹1,188 = **₹5,346 crore ≈ USD 640M** addressable consumer spend.
- Realistic 24-month capture: 250,000 paying users × ₹1,188 = **₹29.7 crore ≈ USD 3.6M ARR**.

**Show the derivation on the slide.** A judge who can follow your arithmetic trusts the number; a judge who cannot assumes you invented it. State the assumptions explicitly: 3.8 subs/household, 100% conversion to paid (then discount it in the SOM), ₹83.5/USD.

### 8.7 Competitive landscape

| Player | Market | What it does well | Where it stops | Our edge |
|---|---|---|---|---|
| **Rocket Money / Truebill** | US | Bank-linked detection, cancellation concierge, price negotiation | US-only, no Indian descriptor handling, no UPI/NACH, English-only | India-first parsing, Hinglish voice, no bank connection required |
| **Trim** | US | Negotiation, bill review | Narrow, US rails | Guided cancellation + audit trail |
| **Bobby** | EU | Manual subscription entry, clean UI | Manual entry — the user must already know | We discover; they record |
| **Bank UPI AutoPay screens** (HDFC/SBI/ICICI/Axis) | India | Authoritative mandate list, free | Raw descriptors, no merchant identity, no annualised total, no advice, no cross-bank view | Identity + insight + action layer on top of the same data |
| **NPCI e-mandate portal** | India | Canonical mandate registry | Infrastructure, not a consumer product | Consumer UX, evidence, guidance |
| **CRED / Jupiter / Fi / Fold** | India | Distribution, payments, some PFM | Subscription review is a side feature, not the product; no descriptor-level resolution | Depth: 3-tier resolver, anomalies, per-item evidence |
| **Chargebee / Recurly / Zoho Billing** | Global B2B | Billing infrastructure for merchants | Serves the *seller*, not the buyer | We are on the subscriber's side — structurally aligned with the user |
| **Excel + bank statement** | Everywhere | Free, familiar | Abandoned by week three | Zero-effort discovery |
| **Doing nothing** | Everywhere | Zero effort | Costs ₹4,000/month | The only competitor that matters |

**Positioning sentence:** *Rocket Money for India's UPI rails, without needing your bank login, in the language you actually think in.*

**The honest competitive risk:** a bank can build this in a quarter and bundle it free. Our answer is that banks have not, because their incentive is to keep mandates alive; a neutral party is the only one who can tell you to cancel. That is a real, defensible structural argument — use it.

### 8.8 Moat

1. **Descriptor corpus.** Every confirmed resolution becomes a labelled example. After 100k Indian transactions, the resolver knows things a new entrant cannot bootstrap. This compounds and it is the actual asset.
2. **Evidence-linked trust.** Competitors show a number. We show why. That is the difference between a dashboard and something you act on.
3. **Language.** Hinglish voice is not a feature parity item; it is a distribution advantage in a market where English-only fintech excludes most of the addressable base.
4. **Neutrality.** We are not the merchant and not the bank. Our incentive is aligned with cancellation, which is exactly why incumbents cannot copy the positioning.
5. **B2B2C distribution.** One bank partnership delivers users at near-zero CAC. That is the scale lever, and it is why the white-label tier exists in the model from day one.

### 8.9 First 100 users

The goal is not 100 signups. It is **100 activated users**, where activated = ≥3 recurring payments detected **and** ≥1 action taken (cancelled, confirmed, or reported unused).

| # | Channel | Tactic | Target | Cost | When |
|---|---|---|---|---|---|
| 1 | Campus pilot (2 colleges, Bengaluru + Pune) | 10-minute demo in a fintech club session; QR code to the live URL; ₹0 | 40 | ₹0 | Week 1 |
| 2 | Coworking pilot (1 space, Indiranagar) | Desk-drop card with a QR; offer a 15-minute "subscription audit" | 20 | ₹2,000 | Week 1 |
| 3 | Fintech creators (3 × 8–15k followers) | Paid post: "I found ₹6,800/year in subscriptions I forgot" with real screen recording | 25 | ₹15,000 | Week 2 |
| 4 | Reddit (`r/personalfinanceindia`, `r/IndiaInvestments`) + X thread | Post the *method*, not the product: "here's how to read your UPI AutoPay descriptors" | 15 | ₹0 | Week 1 |
| 5 | WhatsApp referral loop | Invite 2 friends → 1 month Pro free, both sides | 10 | ₹0 | Week 2 |
| 6 | One bank/NBFC design partner | 10 staff accounts + a feedback session; use it to open the B2B conversation | 10 | ₹0 | Week 3 |

**Total: 120 targeted, expecting ~100 activated.**

**Instrumentation required before launch:** signup source, time-to-first-insight, items detected, actions taken, and drop-off step. Without these you cannot tell which channel worked, and the "first 100" story becomes anecdote.

**What we will not do:** bought installs, engagement pods, or a "waitlist" with no product behind it. In a market where trust is the product, a fake growth number is a liability.

---

## 9. Demo script — 7 minutes

Rehearse to 6:30 so there is slack. Every number below must come from the live database at rehearsal time; if a number changes, change the script, not the data.

| Time | Beat | What is on screen | What you say |
|---|---|---|---|
| 0:00–0:30 | The hook | One line, huge: `RAZORPAY*SPOTIFYIN MUMBAI 649.00` | "This is what your bank shows you. Twenty-eight characters of noise. Ananya has fourteen of these a month and can name seven." |
| 0:30–1:00 | The number | The C+R 2.5× gap and the Ormax 172.6M stat | "People underestimate their subscription spend by two and a half times. In India that is 172 million paid subscriptions, and 175 million AutoPay debits a month." |
| 1:00–1:30 | The promise | Three words: See it. Believe it. Act on it. | "We turn that string into a decision." |
| 1:30–2:20 | Live: clean start | Fresh account, empty state, press "Load demo dataset" | Narrate the empty state. "A first-time user has one obvious button. No instructions needed." |
| 2:20–3:20 | Live: the dashboard | 5 recurring items, ₹14,244 annualised, ₹6,828 savings, 3 active + 2 tentative, 6 anomalies | "Five real subscriptions, found from twenty transactions. Fourteen thousand a year. The number Ananya could not produce." |
| 3:20–4:00 | Live: evidence | Open the Netflix price-hike item; show raw descriptors, dates, amounts, tier | "This is why we are not a black box. Every rupee is traceable to a line in the statement." |
| 4:00–4:40 | Live: the differentiator | Press "Brief me"; Hinglish audio plays | Let it play. Do not talk over it. Then: "That was generated from this database, thirty seconds ago, in the language she thinks in." |
| 4:40–5:20 | Live: the proof | Change the amount in the DB, press "Brief me" again | "Listen to the number change. That is a live model, a live database, and live speech — not a recording." |
| 5:20–6:00 | Live: action | Cancellation playbook → notice → mark requested → audit trail appears | "Knowing is half. This is the other half: the right rail, the right notice, and a timestamped record that it happened." |
| 6:00–6:30 | Honesty | Slide: "What is real / what is next" | "Real: resolution, discovery, anomalies, evidence, voice, audit. Next: bank connectivity and automatic cancellation — we guide and track, we do not pretend to execute." |
| 6:30–7:00 | Close | Live URL + QR + one sentence | "MandateShield. See it, believe it, act on it. It is live at this URL — try it now." |

**Backup plan:** if the network dies, switch to the recorded video without commentary, then return live for Q&A. Never debug on stage.

---

## 10. Judge Q&A and objection handling

Answer in one sentence, then stop. Over-explaining reads as defensiveness.

**1. "Does this actually cancel my subscription?"**
No — and deliberately so. We generate the correct rail-specific notice, track the request, and record confirmation. Automatic execution requires bank and merchant APIs we do not have, and claiming it would be the fastest way to lose your trust.

**2. "Where does the data come from?"**
Today: CSV import or our synthetic demo set. Next: Account Aggregator and UPI AutoPay mandate data. We never ask for bank credentials, which is a security feature, not a limitation.

**3. "Isn't 20/20 merchant accuracy just because you seeded the patterns?"**
Yes, on the tuning set — and we say so. Here is the holdout set we have never tuned on: it scores lower, and that is the number we trust. *(Show the metrics page.)*

**4. "Why not just use the bank's AutoPay screen?"**
It lists mandates, not merchants. `NACH DR HDFC LIFE` tells you nothing, there is no annualised total, no cross-bank view, and no advice. We are the identity and decision layer on top of that data.

**5. "What stops a bank from building this?"**
Nothing technical — and that is the point. Banks have had the data for a decade and have not built it, because their incentive is to keep mandates alive. A neutral party is the only one who can credibly say "cancel this". That is a structural moat, not a feature moat.

**6. "How do you make money?"**
₹99/month or ₹799/year for consumers; ₹12 per active user per month white-labelled to banks and fintechs, with a ₹5 lakh annual minimum. The B2B price is set against their cost of a failed-mandate support call.

**7. "What is your CAC?"**
Target ₹150–₹250 through creators and referrals, with a 2–3 month payback at ₹99 ARPU. It is a target, not a measured number — we have not spent on acquisition yet.

**8. "Why Sarvam and not OpenAI?"**
Two reasons. Sarvam handles Indic transliteration and code-mixed Hinglish, which is exactly what Indian bank descriptors are. And the voice is the product for a user who will not read a dashboard at 8am. Cost is ₹6/user/month at our usage, which is 6% of ARPU.

**9. "Why not Neo4j?"**
Because we do not have a graph problem yet — depth-3, few hundred nodes per user. Neo4j becomes right when we cluster merchant aliases across tenants at scale. We deferred it rather than half-build it.

**10. "What happens if the AI is wrong?"**
The AI never decides the amount or the date. Those come from the database. The model only phrases the sentence, and we validate that its output contains the real values; if not, we fall back to a deterministic template. The resolver shows its confidence tier and matched pattern so the user can judge it.

**11. "Is this DPDP compliant?"**
Not yet fully. We do not store bank credentials, we scope every query to the authenticated user, and we hash passwords. Missing: consent records, deletion, export, and a retention policy — those are on the roadmap, and we will not claim compliance until they exist.

**12. "What is the hardest part you have not solved?"**
Descriptor coverage. Indian payment descriptors are generated by gateways, not merchants, and they mutate. Our answer is a learned pattern corpus that compounds with usage — which is also why the first 100 users matter more than the first 100 rupees.

**13. "Why should this win over the other teams?"**
Because the hard part is already working and the remaining gap is honesty, not features. Three real engines, a real database, real auth, a real deployment, and a differentiator you can hear. Most demos in this category are a mockup with a nice gradient.

**14. "What did you cut?"**
Bank connectivity, automatic cancellation, email/WhatsApp reminders, multi-currency billing, and family accounts. Each one is a partnership or a compliance problem, not a coding problem, and cutting them is why the core is finished.

---

## 11. Risk register

| # | Risk | Likelihood | Impact | Mitigation | Trigger to act |
|---|---|---|---|---|---|
| R1 | Deployment fails or exceeds the window | Medium | Critical | Start at 22:30 sharp; have `docker compose` + recorded video ready by 23:30 | Not live by 00:30 |
| R2 | Atlas network/IP blocks the host | Medium | High | Pre-allow `0.0.0.0/0` on the demo cluster with a least-privilege user; test from a phone hotspot | Connection timeout on first deploy |
| R3 | Sarvam key/quota failure mid-demo | Medium | High | Cache the audio for the two demo items 30 min before; template fallback wired; second key if available | Any 401/429 during rehearsal |
| R4 | A P0 fix regresses the pipeline | Medium | High | Tag before each block; re-run the 9 tests + the 12-point DoD after every P0 | Any test red |
| R5 | Judge deletes data and sees stale state | High if B1 unfixed | Critical | P0-1 ships in block 1; demo account is disposable | B1 repro still failing at 17:30 |
| R6 | Live demo network drops | Medium | High | Local fallback + recorded video + phone hotspot | Any latency > 3s on a click |
| R7 | Judge asks about CI/README claims | High | Medium | Ship `.github/workflows/ci.yml` in block 6, or edit the README | Block 6 starts |
| R8 | Multi-currency edge case surfaces live | Low | Medium | Demo only in INR; footnote the rate; no FX call during demo | Any USD item in the demo account |
| R9 | You run out of time and ship a half-fix | Medium | High | Freeze at 11:15; a disclosed gap beats a broken feature | 11:15 |
| R10 | Sarvam model invents a number on stage | Low | Critical | Validate output against context; template fallback; rehearse the exact items | Any rehearsal mismatch |
| R11 | Demo account contaminated by earlier tests | Medium | Medium | Reset endpoint + a fresh account per rehearsal; verify counts before going on | 30 min before pitch |
| R12 | Overclaiming in Q&A | Medium | Critical | This document §3 is the script; never present-tense a GAP | Always |

---

## 12. Appendix

### A. API surface (as built)

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/v1/auth/register` | No | Create account, return JWT |
| POST | `/api/v1/auth/login` | No | Authenticate, return JWT |
| GET | `/api/v1/auth/me` | Yes | Current user |
| POST | `/api/v1/transactions` | Yes | Ingest one transaction |
| POST | `/api/v1/transactions/import` | Yes | Bulk ingest (1–500) |
| GET | `/api/v1/transactions` | Yes | List own transactions |
| DELETE | `/api/v1/transactions/{id}` | Yes | Delete own transaction (204) |
| POST | `/api/v1/resolver/resolve` | Yes | Resolve a descriptor *(must become dry-run)* |
| GET | `/api/v1/resolver/patterns` | Yes | List visible patterns |
| POST | `/api/v1/resolver/patterns/cache/clear` | Yes | Clear compiled cache |
| POST | `/api/v1/pipeline/run` | Yes | Recompute subscriptions + anomalies |
| GET | `/api/v1/dashboard` | Yes | KPIs for current user |
| GET | `/api/v1/cancellation/playbook` | Yes | Rail/merchant guidance |
| POST | `/api/v1/cancellation/generate-letter` | Yes | Notice text |
| POST | `/api/v1/cancellation/{id}/request` | Yes | Mark requested |
| POST | `/api/v1/cancellation/{id}/confirm` | Yes | Mark confirmed |
| POST | `/api/v1/cancellation/{id}/revoke` | Yes | Mark revoked |
| POST | `/api/v1/cancellation/{id}/report-unused` | Yes | Flag unused |
| POST | `/api/v1/cancellation/{id}/confirm-activity` | Yes | Clear unused flag |
| POST | `/api/v1/demo/seed` | Yes | Seed synthetic dataset |
| GET | `/api/v1/demo/evaluation` | Yes | Score current dataset |
| GET | `/actuator/health` | No | Liveness |

Planned additions: `/api/v1/demo/reset`, `/api/v1/subscriptions/{id}/confirm`, `/api/v1/subscriptions/{id}/dismiss`, `/api/v1/letters`, `/api/v1/voice/brief`, `/api/v1/audit`, `/api/v1/metrics/evaluation`, `/api/v1/auth/export`, `DELETE /api/v1/auth/me`.

### B. Data model (current, plus required changes)

| Collection | Key fields | Change needed |
|---|---|---|
| `users` | id, name, email, passwordHash | Add `createdAt`, `deletedAt` |
| `transactions` | id, userId, rawDescriptor, merchant, amount, currency, category, rail, date, confidence, tier | Add `amountInInr`, `fxRate` |
| `subscriptions` | id, userId, merchant, amount, cadence, nextCharge, status, anomalyFlags | **Add `currency`, `amountInInr`, `supportingTransactionIds`/`lastSupportedAt`, `state`** |
| `patterns` | id, regex, brand, category, confidence, tier, learned | No change |
| `playbooks` | rail, merchant, steps, reviewedOn | Store a real authored date |
| `audit` | — | **New:** userId, subscriptionId, action, from, to, actor, at, metadata |

### C. File map — where to change what

| Goal | File(s) |
|---|---|
| Stale-subscription reconciliation | `src/main/java/com/mandateshield/service/MandateShieldPipelineService.java`, `domain/SubscriptionDocument.java` |
| Resolver dry-run | `engine/MerchantResolverEngine.java`, `controller/` resolver controller |
| Merchant identity (A1) | `controller/TransactionController.java` (uppercase at line 60), `service/MandateShieldPipelineService.java` (grouping + lookup), `domain/SubscriptionDocument.java` (add `merchantKey`) |
| Atomic import + CSV (A2, A3) | `controller/TransactionController.java` (`importTransactions`, `parseDate`), `frontend/src/App.jsx` (`parseCsv`, `importCsv`) |
| Bulk-import performance (A4) | `controller/TransactionController.java`, `service/GeminiMerchantEnrichmentService.java` (`synchronizeKnownTierOne`) |
| Next-charge date (C1) | `engine/discovery/RecurringDiscoveryEngine.java`, `dto/SubscriptionDto.java`, `domain/SubscriptionDocument.java` |
| Cancel URLs (C2) | `domain/MerchantPatternDocument.java`, `service/CancellationAndRevocationService.java`, seed data |
| Dismiss / confirm (C4) | `domain/SubscriptionStatus.java`, new `controller/SubscriptionController.java` |
| Currency | `domain/SubscriptionDocument.java`, `service/ExecutiveDashboardService.java`, transaction DTOs |
| Indexes (B1) | `domain/RawTransactionDocument.java` |
| Signup path (B4, B5) | `service/UserSequenceService.java`, `controller/AuthController.java` |
| Remove fake fallbacks | `frontend/src/pages/ResolverPlayground.jsx`, `frontend/src/pages/CancellationStudio.jsx` |
| CORS | `src/main/java/com/mandateshield/config/CorsConfig.java` |
| Sarvam proxy | new `service/VoiceBriefingService.java` + `controller/VoiceController.java` |
| Audit trail | new `domain/AuditDocument.java`, `repository/AuditRepository.java`, hook into cancellation service |
| Letters (C3) | new `domain/LetterDocument.java`, `controller/CancellationController.java` |
| Landing + demo entry (C7) | `frontend/src/App.jsx`, new `pages/Landing.jsx` |
| Rate limiting (D2) | new `config/RateLimitFilter.java` |
| Deployment | new `Dockerfile`, `render.yaml` or `railway.json`, `frontend/.env.production` |
| CI | new `.github/workflows/ci.yml` |
| Honest metrics | `service/SyntheticEvaluationService.java` + a `/metrics` route in the frontend |
| README truth | `README.md` (lines 234–262) |

### D. Sources

1. Ormax Media via Adgully — India OTT 2026: <https://www.adgully.com/post/20257/india-has-665-million-ott-users-and-173-million-active-paid-subscriptions-ormax>
2. Business Standard citing NPCI — UPI AutoPay growth: <https://www.business-standard.com/finance/personal-finance/upi-autopay-growth-2025-overtakes-cards-payments-npci-125022000316_1.html>
3. IMARC — India subscription & billing management market: <https://www.imarcgroup.com/india-subscription-billing-management-market>
4. YouGov — unused subscriptions: <https://yougov.com/articles/50030-subscription-graveyard-how-many-unused-subscriptions-are-consumers-currently-paying-for>
5. C+R Research — subscription statistics: <https://www.crresearch.com/blog/subscription-service-statistics-and-costs/>
6. Sarvam AI — chat completions: <https://docs.sarvam.ai/api/token-factory/chat-completions.md>
7. Sarvam AI — pricing: <https://docs.sarvam.ai/api/getting-started/pricing.md>
8. Sarvam AI — text processing overview: <https://docs.sarvam.ai/api/api-guides-tutorials/text-processing/overview.md>

**Source hygiene:** every number in §8.2 must have a visible citation on the slide. If a figure cannot be re-opened during Q&A, cut it.

### E. Pre-flight checklist — 11:00 AM, Sunday

Run this in order. Do not skip. Do not "quickly fix" anything after step 8.

- [ ] Production URL loads over HTTPS on a phone, Wi-Fi off.
- [ ] Register a brand-new account; empty state renders.
- [ ] Load demo dataset; 20 transactions, 5 recurring groups, 4 scenarios.
- [ ] Dashboard numbers reconcile by hand: sum of `amount × cycles` for active items.
- [ ] Delete one merchant's transactions; run pipeline; merchant disappears; total drops correctly.
- [ ] Resolve 5 descriptors in the playground; transaction count unchanged.
- [ ] "Brief me" plays audio naming the correct merchant and date.
- [ ] Change an amount; "Brief me" reflects the new amount.
- [ ] Generate a letter; mark requested; audit row appears with a timestamp.
- [ ] Stop the backend; UI shows an error, never a fabricated result.
- [ ] Reset endpoint returns the account to clean state; run twice.
- [ ] `grep -r "HEURISTIC_FALLBACK" frontend/src` → nothing.
- [ ] CI green on the release branch.
- [ ] Recorded backup video plays offline.
- [ ] Slides exported as PDF *and* PPTX.
- [ ] Demo account credentials written on a sticky note, not in the slide deck.

If every box is ticked, the sentence in §0 is true, and the rest is delivery.

---

*Prepared 21 November 2026. Every VERIFIED claim in §3.1 was executed in this session; every bug in §3.2 was reproduced. Where this document and the README disagree, this document is correct — and the README should be fixed before a judge reads it.*
