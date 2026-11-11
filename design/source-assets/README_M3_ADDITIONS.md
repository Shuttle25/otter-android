# Otter M3 — additions only

Extract beside the existing pack. Every delivered filename is new. No old assets or old manifest are included or overwritten.

## New assets

| File in assets/ | Use |
|---|---|
| otter_m3_launcher_foreground.png | Transparent 1024×1024 colour head, including ears and whiskers, fitted inside the centred 620 px safe circle |
| otter_m3_launcher_background.png | Flat opaque 1024×1024 background, exact sRGB mint **#DCF1E3** |
| otter_m3_monochrome_head.svg | One-colour white head with transparent facial cutouts, 1024 viewBox; same safe circle |
| otter_m3_monochrome_head.png | 1024×1024 transparent raster equivalent of the SVG |
| otter_m3_no_chats.png | Transparent 1254×1254 illustration for an empty chat LIST after joining |

## Agent integration

1. Copy the new assets into the existing `otter-assets/assets/` directory. Keep the previous masters unchanged.
2. Read `manifest.m3-additions.json`. Append its `assets` entries to the existing `manifest.json` assets array, matching by `file` so a second import does not duplicate entries. Preserve existing entries and metadata. The differently named additions manifest intentionally avoids replacing the original manifest.
3. Build the adaptive launcher using the new foreground and #DCF1E3 background. The foreground already includes safe-zone padding: do not auto-trim, centre-crop or add another round of safe-zone shrinking. Map the full canvas to the foreground layer and verify launcher masks in the project.
4. Import the monochrome SVG through the project's vector workflow, or use its PNG alpha mask. It uses a single white fill and transparent holes. Preserve the even-odd fill rule and transform; flatten the transform during conversion if needed. The system can tint its alpha. Test the resulting Android drawable, including the splash presentation, rather than assuming SVG is directly loadable as an Android vector resource.
5. Show `otter_m3_no_chats.png` only when the chat list is truly empty after initial sync. Fit at about 160–200 dp, preserving transparency and aspect ratio. Keep all copy in UI: suggested title “Пока нет чатов”, button “Написать”. Do not flash the empty state while loading. The existing `otter_empty_chat.png` remains exclusive to an empty conversation.

## Avatar decisions

**Option (a).** Existing family artwork belongs only to the first/main family group. Every other group uses coloured initials until its members choose a photo. Derive colour deterministically from a stable group id, keep it stable after renaming, and use a contrast-safe text/background pair. Do not infer the main group by its visible name; retain an explicit main-group id/flag. Existing custom avatars always take precedence.

Personal avatars remain coloured initials. No extra personal-face set is planned.

## Later: M5 notifications

The monochrome head is a reusable source shape. A notification drawable is not delivered as a separate asset in this M3 pack. At M5, reframe the shape for a 24 dp notification viewport: remove launcher-only outer padding, keep a modest optical margin, and verify the facial cutouts at actual status-bar size. Do not just shrink the entire padded 1024 canvas and call that the final notification icon. If necessary simplify the mouth cutout at that size. Keep it white with alpha, without a background.

## Verification and limits

PNG dimensions and alpha channels are validated; nonzero-alpha pixels for both icon layers fit inside a radius of 310 px from the canvas centre. `manifest.m3-additions.json` contains measured radii and checksums. The SVG was rendered to verify the same geometry. The monochrome design was visually inspected at a small size. Runtime Android launcher/splash/notification behaviour still requires validation in the app.

The mint colour above is the exact new flat background specification, not a claim that every pixel of the earlier generated master used one flat colour. No website assets, Material action icons, previous illustrations or previous documents are included.
