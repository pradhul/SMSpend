# SMSpend Design System

## Brand

**SMSpend** — SMS + Spend. Private, calm, week-first money clarity from debit SMS.

## Visual direction

- **Tone:** quiet ledger, not fintech neon
- **Metaphor:** inbox of outflows — readable hierarchy, soft paper surface
- **Avoid:** purple gradients, cream+terracotta cliché, dark-mode glow, pill-stat strips, card-stack dashboards

## Color

| Token | Hex | Use |
|-------|-----|-----|
| `--ink` | `#0F3D32` | Primary text, icon bg, key actions |
| `--ink-soft` | `#1A5244` | Secondary emphasis |
| `--mint` | `#A8E6CF` | Accent, selected category bar |
| `--mint-deep` | `#3D9B7A` | Active controls |
| `--paper` | `#F3F0E8` | App background |
| `--paper-raised` | `#FFFbf5` | Sheets / list rows |
| `--line` | `#D9D3C5` | Dividers |
| `--mute` | `#6B736E` | Secondary labels |
| `--debit` | `#B54A3A` | Amounts (outgoing) |
| `--ok` | `#2F6B4F` | Success / synced |

## Typography

- **All UI + totals:** `DM Sans` (clean modern sans) — Stitch token `DM_SANS`
- No decorative serifs; no Outfit/Fraunces
- **Amounts:** tabular lining figures where possible

## Screens (v1)

1. **Onboarding** — permission + Gemini key intro
2. **Home** — this week total + categories by amount
3. **Category** — merchants / transactions in that category
4. **Retag sheet** — pick category + apply to similar this month
5. **Settings** — Gemini API key, rescan, about

## Motion

- Home category bars grow width on first paint (stagger ~40ms)
- Pull-to-refresh: soft ink spinner
- Bottom sheet for retag: spring up from bottom

## Assets

- App icon: `design/assets/smspend-app-icon.png`
- Interactive mockups: `design/mockups/index.html`
