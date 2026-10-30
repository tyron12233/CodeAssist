# About this remix

**CodePrimer** is a remix of [CodeAssist](https://github.com/tyron12233/CodeAssist) by tyron12233.
CodeAssist is free software under the GNU General Public License v3.0 or later (see `LICENSE` and
`LICENSE-EXCEPTION`). CodePrimer is distributed under the same license, and its complete source is this
repository. Original copyright notices are kept as they were. This is not the official CodeAssist app and
is not affiliated with or endorsed by its author.

Base: upstream `main` at commit `ee24e06` (2026-10-29).

## What changed from upstream

**Identity, so it installs next to the official app**
- `applicationId` is `io.github.veelex.codeprimer` (was `com.tyron.code`). The Kotlin source package
  (`dev.ide.android`) is unchanged.
- Launcher name and user-visible text (all locales, including the agent and VCS screens) say "CodePrimer".
  That covers the strings hardcoded in Kotlin too: the splash screen, home header, build notifications, the
  Files-app root, toasts, import/convert messages, the first Learn lesson, and the `createdBy` stamp on exported
  `.caproj` packages.
- Deep-link scheme is `codeprimer://` (was `codeassist://`), so the two apps do not compete for the same
  links. Changed in the manifest, `StoreAuth`, and the tests that assert it.
- Desktop bundle ID is `io.github.veelex.codeprimer.desktop`.

**Upstream's accounts and services are no longer used by default**
- Release builds use Google's AdMob *test* ads instead of the upstream author's ad units. To show real ads,
  pass your own `-PADMOB_APP_ID`, `-PADMOB_NATIVE_UNIT_ID`, `-PADMOB_INTERSTITIAL_UNIT_ID` (and optional
  `-PADMOB_NATIVE_UNIT_<PLACEMENT>`).
- Supabase URL/key, the daily-challenge judge URL and the store media URL default to blank. The Projects Store
  falls back to its bundled catalog, analytics is a no-op, challenge submission is disabled, and push
  registration is inert. Point them at your own services with `-PSUPABASE_URL`, `-PSUPABASE_KEY`,
  `-PJUDGE_URL` and `-PSTORE_MEDIA_URL` if you run your own.
- In-app Feedback, Privacy, Star and Sponsor links point to this repository.
- `PRIVACY.md` is rewritten for this build.

**Left as upstream on purpose**
- Internal names (`CodeAssistApp`, packages under `dev.ide.*`, `.caproj`, the plugin SPI, the `codeassist`
  color-scheme id), so upstream changes stay easy to merge and CodeAssist plugins keep working.
- Plugin templates and the `samples/` plugins still say "CodeAssist", because plugins are built for the CodeAssist
  plugin API. The CLI, the MCP server and the desktop launcher's own text are also unchanged.
- Copyright headers.
- The "Join Discord" link still opens the upstream community invite.

## Building your own signed release

Use your own keystore (see `keystore.properties.example`). Do not reuse upstream's signing keys or Play listing.
