# Design System Specification: The Command Sentinel

## 1. Overview & Creative North Star
The Creative North Star for this system is **"The Tactical Hologram."** 

Unlike standard dashboards that feel like flat spreadsheets, this system treats the UI as a high-performance light-projection. It prioritizes depth, luminosity, and data-density without the clutter of traditional borders. We move beyond "Modern Dark Mode" into a bespoke, editorial cybersecurity experience that feels like a live mission control. 

The aesthetic is driven by **Intentional Asymmetry**. Large, high-contrast display typography anchors the layout, while technical "micro-data" orbits the periphery. We use overlapping glass layers to create a sense of physical machinery humming beneath a polished glass surface.

---

## 2. Colors & Surface Philosophy
The palette is built on high-chroma accents against a void-like foundation. 

### The "No-Line" Rule
**Prohibit 1px solid borders for sectioning.** Structural separation is achieved through background shifts.
- Use `surface-container-low` (#181C22) for the base navigation.
- Use `surface-container-highest` (#31353C) for active focal points.
- Transitions must be tonal, never outlined.

### Surface Hierarchy & Nesting
Treat the UI as a stack of optical lenses. 
- **Base Layer:** `surface` (#10141A).
- **Secondary Tier:** `surface-container` (#1C2026) for large content areas.
- **Action Tier:** `surface-container-high` (#262A31) for interactive cards.
- **Highlight Tier:** `surface-bright` (#353940) for rare, high-elevation modals.

### The "Glass & Gradient" Rule
To achieve a "King of Lab" premium feel, floating panels must use **Glassmorphism**.
- **Effect:** 60% opacity of `surface-container-lowest` (#0A0E14) with a 20px `backdrop-blur`.
- **Signature Texture:** Primary CTAs should utilize a linear gradient from `primary_container` (#00F0FF) to `secondary_container` (#7701D0) at a 135-degree angle to simulate light refraction.

---

## 3. Typography
We utilize a dual-font system to balance editorial authority with technical precision.

*   **Display & Headlines (Space Grotesk):** These are your anchors. Use `display-lg` (3.5rem) for critical threat levels or high-level metrics. The wide tracking and geometric forms suggest a futuristic, high-end "Command" presence.
*   **Body & Technicals (Inter):** Inter provides the readability required for log files and threat descriptions. 
*   **The Technical Pulse:** Use `label-sm` (Inter) in all-caps with 0.1rem letter spacing for metadata (e.g., TIMESTAMP, IP_ADDRESS). This creates the "terminal" feel without sacrificing the premium editorial aesthetic.

---

## 4. Elevation & Depth
Depth is a functional tool in this system, not just an ornament.

*   **The Layering Principle:** Place a `surface-container-lowest` card on a `surface-container-low` background. The slight darkening creates a "recessed" look, suggesting the card is a window into a deeper system layer.
*   **Ambient Glow (Shadows):** Replace drop shadows with "Ambient Glows." Use the `primary_container` (#00F0FF) color at 5% opacity with a 40px blur for high-priority alerts. It should feel like the screen is radiating light.
*   **The "Ghost Border" Fallback:** If accessibility requires a container edge, use `outline-variant` (#3B494B) at 15% opacity. It must be felt, not seen.
*   **Luminous Depth:** Apply a subtle 1px inner-glow (top-left) to glass panels using `primary` (#DBFCFF) at 10% opacity to simulate the edge of a glass pane.

---

## 5. Components

### Buttons
*   **Primary:** Gradient fill (`primary_container` to `secondary_container`), no border, `on_primary_fixed` text. 
*   **Secondary:** Glass background (`surface-container-high` at 40%), `outline` at 20% opacity.
*   **States:** On hover, increase `backdrop-blur` and add a 2px `primary` outer glow.

### Chips (Threat Tags)
*   **Danger:** `error_container` background with `on_error_container` text. No border.
*   **Success:** `tertiary_container` background with `on_tertiary_container` text.
*   **Selection:** Use `primary_fixed` with a "pulsing" animation on the leading icon to indicate active monitoring.

### Input Fields
*   **Styling:** `surface-container-lowest` background, `md` (0.75rem) corners.
*   **Active State:** The bottom edge glows with a 2px `primary_container` line. Avoid boxing the entire input; let the depth of the container define the shape.

### Cards & Lists
*   **Forbid Dividers:** Separate list items using the spacing scale (e.g., `spacing-4` / 0.9rem). Use a subtle background hover state (`surface-bright` at 5%) to indicate interactivity.
*   **Asymmetric Data:** Don't align everything to a center axis. Group technical metrics to the right using `label-sm` and primary descriptions to the left using `title-md`.

### Specialized Component: The "Pulse Monitor"
A custom sparkline component using `primary` (#DBFCFF) for the line, with a `primary_container` (#00F0FF) gradient fill underneath, fading to 0% opacity. This visualizes real-time data flow with high-performance elegance.

---

## 6. Do’s and Don’ts

### Do:
*   **Embrace Negative Space:** Use `spacing-12` (2.75rem) between major modules to let the data "breathe."
*   **Use Intentional Color:** Only use `secondary` (Purple) for "Advanced" or "AI" features. Keep `primary` (Cyan) for standard operations.
*   **Layer Containers:** Nesting a `surface-container-high` element inside a `surface-container` creates a premium "machined" feel.

### Don’t:
*   **Don't use 100% White:** Never use #FFFFFF. Use `on_surface` (#DFE2EB) to prevent eye strain in dark environments.
*   **Don't Use Solid Borders:** Avoid the "Bootstrap" look. If a section needs to be separated, change the background color or increase the padding.
*   **Don't Over-Glow:** Glows are for critical status only. If everything glows, nothing is important. Keep the "Tactical Hologram" focused.