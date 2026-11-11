# Otter assets — implementation instructions

Use these assets in the existing Kotlin / Jetpack Compose Android messenger. Preserve the existing architecture and app id `app.ottermsg`. This package contains four original PNG masters, not a complete Android resource bundle. Do not regenerate or redraw the mascot.

## Inventory and placement

| File | Intended use | Suggested display size |
|---|---|---|
| `assets/otter_app_icon_master.png` | Source for launcher icon; optional default personal avatar | Launcher-controlled; avatar 40–64 dp |
| `assets/otter_family_avatar.png` | Default family-group avatar only; user-selected images take precedence | 40 dp chat header, 48 dp list, 64 dp group info |
| `assets/otter_welcome.png` | Welcome/onboarding illustration | 160–220 dp, responsive |
| `assets/otter_empty_chat.png` | Empty conversation illustration | 120–160 dp, responsive |

All masters are 1254 × 1254 pixels. The icon is RGB with an opaque mint background. The other three images are RGBA with genuine transparency. Their internal transparent margins are intentional. `manifest.json` records dimensions, alpha ranges and SHA-256 hashes.

## Integration order

1. Inspect the existing theme, resources and feature modules. Reuse them rather than creating a second design system.
2. Keep the masters unchanged in the repository's design/source-assets directory. Prepare runtime resources separately using lowercase snake_case filenames.
3. Add welcome art to `feature/onboarding`, family avatar to group/chat headers, and empty-chat art to `feature/conversation`. Keep reusable avatar/image wrappers in `core/ui` as appropriate.
4. Generate launcher resources using the project's Android tooling. Do not simply rename the master to `ic_launcher` and declare the job finished.
5. Validate light/dark themes, small avatars, launcher masks, large text and keyboard visibility on actual app screens.

## Image rendering rules

- Use aspect-preserving Fit for welcome and empty-chat art. Never stretch the PNG or crop its tail, ears or paws.
- The family illustration already contains a mint circular backdrop. Fit it inside the circular avatar bounds; inspect the final mask so no face or tail is cut off. Do not use aggressive center-crop to remove its transparent margin.
- Preserve alpha in runtime exports. The black background sometimes shown by image viewers is not a background to paint into the asset.
- Do not tint multicolour artwork with the theme's icon tint. Render it as an Image, not a monochrome Icon.
- Put runtime illustrations in `drawable-nodpi` when intentionally controlling their displayed size in Compose, or generate the project's established density-specific resources. Avoid accidental density-dependent sizing.
- Downscale/compress copies if necessary for app size; never overwrite masters. Inspect edges on both warm ivory and dark green backgrounds before accepting an export. These are generated raster illustrations, not vectors.
- Keep important text outside the image. Suggested empty-chat copy: “Пока нет сообщений” and “Напишите первое сообщение”. Hide or reduce the decorative illustration when the keyboard or large text leaves insufficient room.
- If accompanying text already expresses the purpose, mark the illustration decorative for accessibility. Give actionable avatars a meaningful description through their parent control.

## Android launcher icon — explicit limitation

The supplied icon is a flattened master, not a separated adaptive foreground. It has neither a monochrome themed layer nor a notification silhouette. Do not claim those are supplied.

Use the current Android Studio Image Asset workflow or the repository's existing launcher-resource generator. For a first pass, import the complete square image and configure scale/background so the face stays intact under circle, squircle and rounded-square masks. Inspect the result at small launcher sizes. The mint background is already baked in; avoid an obvious seam with any added background. Do not pre-round the corners.

For a polished layered adaptive icon, create a separate foreground from this exact master and a matching background, preserving the face. That extraction/export is follow-up implementation work: the current package does not supply it. Verify safe-zone placement in tooling rather than assuming the artwork already meets every adaptive mask. Use a separately designed simple monochrome silhouette if themed icons or notification icons are implemented; do not use the full-colour PNG as the small notification icon.

## Product and visual constraints

- V1: text and photos. No attachment/file picker, voice recorder, video or call controls.
- Reactions and typing indicators must only appear if supported by the actual protocol and application state.
- Family art is a default illustration, not a representation of the actual member count. Show the real member count separately.
- Keep the established warm ivory, mint and deep teal UI. The artwork's brown/cream colours are branding accents, not a new palette for controls.
- Do not cover every screen with mascots. Use the welcome art once, the empty-chat art only when the conversation is empty, and the family art as an avatar.
- Follow `08-otter-design-guidelines.md` and `07-android-architecture.md` from the project when available. Those documents are not bundled here. This README does not override current code or product requirements.

## Acceptance checklist

- All four assets are mapped to their intended roles, and user avatars are not overwritten.
- No opaque rectangle appears around the three transparent assets.
- No image stretches, no important feature is clipped, and small faces remain recognizable.
- Light and dark backgrounds reveal no unacceptable fringe around transparent edges.
- Welcome and empty-chat content remains usable with 200% text and a small screen.
- Launcher icons are tested with multiple masks; the master remains preserved.
- Delivery notes distinguish completed launcher exports from any missing adaptive/monochrome layers.

## Task for the coding agent

Integrate this asset pack into the existing Otter Android application using the mappings and constraints above. First inspect the repository. Preserve unrelated changes. Implement the image resources, screen placements and launcher resources; validate them using the project's normal build and relevant UI checks. Report changed files, checks performed, and any remaining launcher-layer work. Do not add new messaging features as part of this task.
