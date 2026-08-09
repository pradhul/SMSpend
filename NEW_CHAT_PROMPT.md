# SMSpend — paste this when starting a new chat in this folder

Copy everything below the line into the first message of a new Agent chat (after you’ve added designs/icons, or when you’re ready for the next phase).

---

You are helping me build **SMSpend**, a private Android expense app.

## Product

SMSpend turns bank/UPI **debit SMS** into a **this-week spend breakdown by category** (Swiggy, Amazon, grocery, etc.).

- Audience: me and my wife — **separate phones**, each sees only their own SMS (no shared household sync in v1).
- Stack: **Kotlin + Jetpack Compose**.
- Privacy: on-device storage; no bank login; no cloud sync of transactions in v1.

## Core behavior

1. **First launch:** SMS permission + Gemini API key in Settings → scan SMS from the **1st of this calendar month** → parse → categorize → show home.
2. **Every later open:** incremental rescan from last sync (Approach 1 — on-open rescan). Also support **pull-to-refresh**.
3. **No background sync** in v1.

## Categorization (hybrid)

1. Local merchant memory (user corrections)
2. Built-in rules for known merchants (Swiggy, Amazon, BigBasket, etc.)
3. **Gemini** only for unknowns → save result to local memory
4. Fallback category **Other** if offline / no key / API failure

Each user pastes **their own Gemini API key** in Settings.

## Manual fixes

User can retag a transaction’s category and choose **apply to all similar this month** (same merchant / similar match). That updates local memory.

## Home screen

Optimize for **this week by category**: week total + categories ranked by amount → tap category → merchants/transactions.

## Starter categories (fixed set + memory; not a full category editor in v1)

Food delivery, Shopping, Grocery, Fuel, Travel, Bills, Transfers, Other — plus smart/Gemini assignment into these.

## Explicitly out of v1

- Shared household / couple combined view
- Bank account linking / UPI app APIs
- Background workers for SMS
- Budgets, deep analytics, Play Store polish
- Fully custom categories from day one

## Design (already in repo)

- Design system: `design/DESIGN.md` (ink green + mint + paper; **DM Sans**)
- App icon: `design/assets/smspend-app-icon.png`
- Local mockups: `design/mockups/index.html`
- **Stitch exports (preferred UI source):** `design/stitch/screens/` (home, category, retag, settings, onboarding)
- Stitch project ID: `13330119455379583671`
- Design system asset: `assets/16555936527553146493`
- Stitch MCP setup notes: `design/STITCH_SETUP.md`

Follow Stitch screen structure + DESIGN.md tokens unless I ask to change them.

## Current phase / constraints

- Android app lives at repo root (`app/` module). Open in Android Studio.
- Prefer editing existing architecture over re-scaffolding.
- When extending: Kotlin + Compose, Room, SMS read, on-open incremental scan, hybrid categorization.
- Prefer a clean architecture suitable for a small personal app (not over-engineered).
- Name branding: **SMSpend** (SMS + Spend).
- Typography: **DM Sans** / system sans (no Fraunces / Outfit).

## What I want from you in this chat

[REPLACE THIS LINE with your actual ask, e.g. “Help me define icon + color system” or “Review these Figma exports” or “Scaffold the Android project now”]
