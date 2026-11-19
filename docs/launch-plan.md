# Forge discoverability plan

Discoverability presentation and publication reference. The README and static site preserve the current Android release; selecting an overall code license remains a separate owner decision.

## Repository description and topics

**Proposed description**

> Offline workout and nutrition tracker for Android: log sets, build routines, track progress, and keep a food diary. No accounts, ads or analytics.

**Proposed topics**

`android`, `fitness`, `workout-tracker`, `strength-training`, `gym-log`, `nutrition`, `calorie-tracker`, `food-diary`, `offline-first`, `privacy`, `kotlin`, `jetpack-compose`, `material3`, `room-database`

These describe implemented features and technologies. GitHub allows up to 20 topics, using lowercase letters, numbers and hyphens, with at most 50 characters per topic. Apply them through the repository's **About → Edit** controls after approval. [GitHub topic guidance](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/classifying-your-repository-with-topics)

## Social preview recommendation

Create a **1280 × 640 PNG under 1 MB** with Forge's existing charcoal background, lime accent and original project mark. Use the headline **“Your workouts. Your nutrition. On your phone.”**, the subline **“Offline tracking for Android 8.0+”**, and genuine Home and Nutrition screenshots. Keep the text large and leave space around the edges for different social crops. Do not add ratings, user counts, testimonials, store badges or imagined app screens.

The existing screenshots were captured on a Forge 1.3.0 Android 17 emulator with synthetic QA data; their training/nutrition layouts are retained in 1.4.2. Preserve this provenance in the README and site rather than labeling the images as newly captured 1.4.2 screens. A future refresh should capture the production app with synthetic data only.

GitHub recommends 1280 × 640 for best display, accepts PNG/JPG/GIF under 1 MB and uses **Settings → General → Social preview → Edit → Upload an image**. This is an image brief, not an uploaded preview. [Official social preview requirements](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/customizing-your-repositorys-social-media-preview)

## GitHub settings requiring owner approval

| Setting | Proposed action after approval |
| --- | --- |
| About | Set the description and topics above. |
| Website | Add the actual GitHub Pages URL only after the approved site is deployed and checked. |
| Pages | After the approved files are pushed, choose **Settings → Pages → Build and deployment → Source → Deploy from a branch → main → /docs → Save**. |
| Social preview | Upload the approved image described above. |
| Issues | Keep the bug/feedback route available; public reports must omit personal backups and private records. |
| Discussions | Optional later choice for general questions. The prepared pages use Issues and do not depend on Discussions being enabled. |

The landing-page entry is [`index.html`](index.html), with styles in `site/` and genuine images in `screenshots/`. It fits GitHub's supported `/docs` branch source without a new deployment workflow; `.nojekyll` retains the static files as supplied. Enabling that source makes the committed contents of `/docs` public, so retain only public documentation there. Publishing these files also requires configuring Pages as described above. [GitHub Pages publishing sources](https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site)

## Code licensing: a separate owner decision

**No overall license has been chosen or added.** The existing [third-party notices](../THIRD_PARTY_NOTICES.md) and food-data licenses remain in effect independently. Do not label Forge's original code “open source” until an appropriate license is selected.

| Option | Main tradeoff |
| --- | --- |
| [MIT](https://choosealicense.com/licenses/mit/) | Simple permissive reuse, including commercial and closed-source derivatives, while retaining copyright/license notices. |
| [Apache 2.0](https://choosealicense.com/licenses/apache-2.0/) | Permissive reuse with an explicit contributor patent grant and requirements to retain notices and identify changes. |
| [GPLv3](https://choosealicense.com/licenses/gpl-3.0/) | Strong copyleft: distribution of covered modified/combined works carries corresponding-source and same-license obligations. |
| Retain the current unlicensed status, or consider custom source-available terms | Public visibility does not grant broad reuse rights. Custom restrictions need deliberate drafting and are not automatically an open-source license. |

Without a license, ordinary copyright restrictions apply; GitHub's terms still permit viewing and forking on its service. Choosing reuse rights for original code does not relicense third-party datasets, dependencies, trademarks or other contributors' work. Review ownership and compatibility before choosing terms. [GitHub's explanation of no license](https://choosealicense.com/no-permission/)

## Early-user launch checklist

1. Approve the README, landing-page presentation and metadata. Publish only those approved materials; retain the current v1.4.2 release and production signing identity.
2. Check the official APK and checksum links from a phone, and complete a fresh Android 8.0+ installation. For old development-signed installations, check the external portable backup **before** the manual uninstall/reinstall/restore transition. Follow the [signing/migration guide](signing.md); optional food packs need downloading again after reinstall.
3. Once Pages is live, check narrow-screen layout, screenshot loading, keyboard navigation, download links, installation instructions and the prominent migration warning. Add that verified URL to About.
4. Invite a small group of Android testers through channels where sharing is permitted. Ask them to log a workout, complete a set/rest, create a routine, log a food and test a backup preview. Request device/Android version and reproducible steps for problems; do not promise uniform physical-device performance.
5. Collect feedback through [GitHub Issues](https://github.com/ritikverma098/Forge-Fitness-Tracker/issues). Ask testers to redact screenshots/logs and never post personal backup files, signing files or tokens. Keep the download/restore path clear while triaging reports.
6. Review recurring issues before broader promotion. Report actual findings and completed fixes, with no invented usage statistics or unverified feature claims.
