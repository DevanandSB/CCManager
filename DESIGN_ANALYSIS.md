# Design Analysis: Google Stitch "Private Vault" Design System

## 1. Aesthetic Philosophy & Overview

The **Google Stitch "Private Vault"** design system represents an ultra-fluid, iOS-inspired **Material 3 tonal elevation** design tailored specifically for on-device personal wealth and credit card management.

### Key Pillars:
- **Calm Financial Clarity**: Clean, distraction-free surfaces paired with editorial typography.
- **Obsidian & Champagne Gold Luxury**: Moves away from generic vibrant tints to timeless, private-banking neutrals (Obsidian Graphite `#111216` and Champagne Gold `#EAC083` in dark mode; Warm Porcelain `#F8F8FA` and Deep Obsidian `#171B27` with Brushed Bronze `#795926` in light mode).
- **Tactile Squircles & Fluid Surfaces**: Generous corner radii (`28px` for physical card faces and hero sheets; `20px` for bill cards and grouped setting containers).
- **Frosted Translucent Navigation**: Sticky headers with `80%` semi-opaque background, subtle backdrop blur, and low-contrast `1px` hairline borders.
- **Tabular Figures & Monospace Accents**: All currency figures and percentages use tabular digits (`tabular-nums`), while card numbers and reference codes are styled with `Source Code Pro`.

---

## 2. Color System & Design Tokens

### 2.1 Dark Mode (Primary Experience)

| Token Name | Hex Code | Role / Usage |
| :--- | :--- | :--- |
| `dark-background` | `#111216` | Main screen background (Deep Obsidian Graphite) |
| `dark-surface` | `#111216` | Base surface level |
| `dark-surface-container-lowest` | `#0C0D11` | Deep recessed containers & shade background |
| `dark-surface-container-low` | `#181A20` | Secondary grouped containers |
| `dark-surface-container` | `#1E2027` | Standard elevated cards & list containers |
| `dark-surface-container-high` | `#282B33` | Interactive chips, icon button backgrounds |
| `dark-surface-container-highest`| `#32353F` | Track backgrounds for progress bars & gauges |
| `dark-primary` | `#EAC083` | Primary action buttons, active tabs, gold accents |
| `dark-on-primary` | `#291800` | Text/icon on primary gold buttons |
| `dark-primary-container` | `#614413` | Tonal button containers & active pill containers |
| `dark-on-primary-container` | `#FFEEDB` | Text/icon on gold container |
| `dark-secondary` | `#C3C6D6` | Brushed Platinum secondary labels & icons |
| `dark-on-surface` | `#E8E9EE` | High-contrast body text & titles |
| `dark-on-surface-variant` | `#B8BAC4` | Subdued secondary labels, timestamps, subtitles |
| `dark-outline` | `#8E9096` | Primary dividers & form field borders |
| `dark-outline-variant` | `#363842` | Hairline borders (0.6dp - 1dp) on cards & lists |

### 2.2 Light Mode (Daytime Experience)

| Token Name | Hex Code | Role / Usage |
| :--- | :--- | :--- |
| `light-background` | `#F8F8FA` | Warm Porcelain base background |
| `light-surface` | `#F8F8FA` | Base surface level |
| `light-surface-container-lowest` | `#FFFFFF` | Elevated white cards & tiles |
| `light-surface-container-low` | `#F2F2F5` | Secondary subtle grouped containers |
| `light-surface-container` | `#ECECEF` | Interactive inputs & button hover/active states |
| `light-surface-container-high` | `#E4E4E8` | Accent chip & pill backgrounds |
| `light-surface-container-highest`| `#DCDCE2` | Inactive progress track |
| `light-primary` | `#171B27` | Deep Obsidian Ink primary buttons & bold headings |
| `light-on-primary` | `#FFFFFF` | Text on obsidian buttons |
| `light-secondary` | `#795926` | Brushed Bronze accents & secondary actions |
| `light-secondary-container` | `#F4DFC5` | Warm bronze containers & highlight tags |
| `light-on-surface` | `#18191D` | High-contrast dark charcoal text |
| `light-on-surface-variant` | `#46464C` | Medium-contrast secondary text |
| `light-outline-variant` | `#C7C6CC` | Soft hairline borders |

### 2.3 Semantic Status & Alert Colors

| Semantic State | Base Color | Container Color | Usage |
| :--- | :--- | :--- | :--- |
| **Success** | `#0E7C47` | `#D1F7E3` (on: `#064E2B`) | Paid bills, connected accounts, verification badges |
| **Warning** | `#9A5800` | `#FFEDD5` (on: `#6B3B00`) | Upcoming bill (<5 days), attention required |
| **Error / Urgent**| `#BA1A1A` (Light) / `#FFB4AB` (Dark) | `#FFDAD6` (Light) / `#93000A` (Dark) | Overdue bills, bills due <2 days, destructive actions |

### 2.4 Physical Card Brand Gradients

| Gradient Name | Start Color | End Color | Text Color |
| :--- | :--- | :--- | :--- |
| `card-amazon` | `#14151A` (Matte Black) | `#262830` (Graphite) | `#FFFFFF` |
| `card-slice` | `#B81450` (Vibrant Pink) | `#820D3B` (Deep Rose) | `#FFFFFF` |
| `card-coral` | `#9A3412` (Coral Rust) | `#B83B0B` (Burnt Orange) | `#FFFFFF` |
| `card-infinia` | `#1C1D24` (Midnight Dark) | `#0E1015` (Pure Obsidian) | `#EAC083` (Gold) |

---

## 3. Typography Scale & Hierarchy

The Stitch design system specifies three distinct font families:

1. **Mulish** (Geometric Sans, ExtraBold 800 & Bold 700): Used for editorial headline figures, cardholder names, total wealth summaries, and screen titles.
2. **Instrument Sans** (Modern Grotesque, SemiBold 600 & Regular 400): Functional UI body text, list items, input fields, and settings labels.
3. **Source Code Pro** (Monospace, SemiBold 600 & Medium 500): Card numbers (`•••• 4321`), CVVs, expiry dates, statement reference codes, and all currency figures (`tabular-nums`).

| Design Token | Family | Size | Weight | Line Height | Tracking |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `display-lg` | Mulish | 40sp | ExtraBold (800) | 46sp | -0.75sp |
| `display-lg-mobile` | Mulish | 32sp | ExtraBold (800) | 38sp | -0.50sp |
| `headline-lg` | Mulish | 24sp | ExtraBold (800) | 30sp | -0.30sp |
| `headline-md` | Mulish | 20sp | Bold (700) | 26sp | -0.20sp |
| `title-lg` | Mulish | 18sp | Bold (700) | 24sp | -0.10sp |
| `title-md` | Instrument Sans | 16sp | SemiBold (600) | 22sp | 0.00sp |
| `title-sm` | Instrument Sans | 14sp | SemiBold (600) | 20sp | 0.00sp |
| `body-lg` | Instrument Sans | 16sp | Normal (400) | 24sp | 0.00sp |
| `body-md` | Instrument Sans | 14sp | Normal (400) | 20sp | 0.00sp |
| `body-sm` | Instrument Sans | 12sp | Normal (400) | 16sp | 0.10sp |
| `label-lg` | Instrument Sans | 14sp | SemiBold (600) | 18sp | 0.10sp |
| `label-md` | Instrument Sans | 12sp | SemiBold (600) | 16sp | 0.20sp |
| `label-sm` | Instrument Sans | 10sp | SemiBold (600) | 14sp | 1.40sp (all-caps) |
| `mono-md` | Source Code Pro | 14sp | SemiBold (600) | 20sp | 0.50sp |
| `mono-sm` | Source Code Pro | 12sp | Medium (500) | 16sp | 0.40sp |

---

## 4. Spacing, Layout Grid & Corner Radii

### 4.1 Spacing Rhythm
- **Base Grid**: Strict 4dp / 8dp rhythm.
- **Page Margin (Gutter)**: `20dp` horizontal padding across all mobile screens.
- **Tokens**:
  - `gutter-xs`: `4dp`
  - `gutter-sm`: `8dp`
  - `gutter-md`: `16dp`
  - `gutter-lg`: `20dp`
  - `gutter-xl`: `24dp`
  - `section`: `32dp`

### 4.2 Shapes & Corner Radii
- `rounded-sm`: `8dp` (small badges, micro chips)
- `rounded-DEFAULT`: `12dp` (form inputs, small tiles)
- `rounded-md`: `16dp` (bento sub-cards, nested tiles)
- `rounded-lg`: `20dp` (bill carousel items, statement rows, grouped containers)
- `rounded-xl`: `28dp` (physical card surfaces, hero summary cards, bottom sheet tops)
- `rounded-full`: `9999dp` / `CircleShape` (filter pills, circular action buttons, status dots)

---

## 5. Elevation, Depth & Translucency

Depth is crafted through **tonal layering** and **frosted translucency** rather than heavy blurred drop shadows:
- **Tonal Layers**: `surface` (base) -> `surface-container-low` -> `surface-container` -> `surface-container-high` -> `surface-container-highest`.
- **Frosted Top App Bar**: Fixed sticky header rendered with `surface.copy(alpha = 0.80f)` combined with blur and a `0.6dp` border of `outlineVariant.copy(alpha = 0.40f)`.
- **Card Shadows**: Ambient, soft shadows (`12dp - 20dp` elevation with alpha `< 0.20f`) applied exclusively to physical card representations and primary floating CTA buttons.

---

## 6. Motion & Micro-interactions

The system uses an **iOS-inspired quint-out deceleration curve**:
- **Easing**: `cubic-bezier(0.22, 1, 0.36, 1)`
- **Surface Enter Duration**: `320ms`
- **Surface Exit Duration**: `200ms`
- **State Change Duration**: `150ms` (ease-out for toggles, pills, and button clicks)

---

## 7. Component Library Summary

1. **Frosted Contextual Top Bar**: Sticky header featuring brand eyebrow (`PRIVATE VAULT`), user name or screen title, circular back button or settings icon, and action CTA.
2. **Circular Utilization Gauge**: 64dp circular gauge with animated arc visualizing credit limit usage percentage.
3. **Filter Pills**: Compact pill row (`All`, `Credit`, `Debit`) with high-contrast active fill and border inactive state.
4. **Physical Brand Card Face**: 28dp squircle with authentic gradients, bank logo, network emblem, EMV chip, contactless glyph, masked card number, and due amount.
5. **Upcoming Bills Carousel**: Horizontal scrolling cards with urgency badge (`2d left`, `4d left`), due date, and amount.
6. **Due & Limit Bento Grid**: Asymmetric 2-column bento tile showing due amount with "Mark as paid" CTA, and credit limit with linear utilization bar.
7. **Proportional Spend Breakdown Bar**: Segmented horizontal bar displaying categorical spending ratios with ranked category rows.
8. **Camera OCR Scanner Reticle**: 1.58:1 card aspect ratio frame with 4 corner brackets, animated sweep laser beam, and verification checkmark emblem.
9. **Biometric Vault Barrier**: Concentric glowing rings around a fingerprint sensor icon, reassuring security pillars, and bottom "Unlock Vault" pill button.
10. **Android Notification Shade Simulator**: Realistic notification card showcasing BigPictureStyle expanded preview with action buttons ("Mark as paid", "Remind tomorrow").
