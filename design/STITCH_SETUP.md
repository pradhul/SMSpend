# Stitch status

Cursor’s Stitch MCP entry is configured, but tools were not exposed to the agent in-session. Screens were generated via `@google/stitch-sdk` against the same API.

## Project

- Stitch project: `13330119455379583671`
- Design system: `assets/16555936527553146493` (DM Sans, ink `#0F3D32`)
- Open in Stitch: https://stitch.withgoogle.com/ (find project SMSpend / recent)

## Local exports

`design/stitch/screens/`

| Screen | Files |
|--------|--------|
| Home | `home.png`, `home.html` |
| Category | `category.png`, `category.html` |
| Retag | `retag.png`, `retag.html` |
| Settings | `settings.png`, `settings.html` |
| Onboarding | `onboarding.png`, `onboarding.html` |
| Gallery | `index.html` |

## App icon

Generated with Stitch (`design/stitch/generate-icon.mjs`) → `design/stitch/icon/icon-source.png`, then copied into adaptive/mipmap densities under `app/src/main/res/`.

Regenerate icon: `node design/stitch/generate-icon.mjs`
