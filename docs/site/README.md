# Forge landing page

Static promotional page for GitHub Pages. It uses the existing application screenshots and does not change Android code, APKs or signing.

## Preview

From the repository root, serve only the public documentation folder:

```text
python -m http.server 8765 --bind 127.0.0.1 --directory docs
```

Open `http://127.0.0.1:8765/`. Stop the server with Ctrl+C. The page can also be opened directly from `docs/index.html`.

The page uses plain HTML/CSS, the Forge Dark palette and icon, system fonts and genuine Forge 1.4.3 emulator images in `docs/screenshots/`. Screenshots contain synthetic test records. The page has no JavaScript, package dependencies, forms, analytics, cookies or remote fonts.

## GitHub Pages

The site uses **Settings → Pages → Build and deployment → Deploy from a branch → main → /docs**. `docs/index.html` is the entry point and `.nojekyll` keeps the site static. Relative asset paths work under the repository Pages URL.

Once GitHub confirms the deployed URL, verify it before adding it to the repository's About website field. The default project URL is `https://ritikverma098.github.io/Forge-Fitness-Tracker/`. A custom domain is optional and unconfigured.

GitHub Pages serves the `/docs` folder, including its public documentation. Keep signing files, personal records and private configuration outside it and outside the repository.

## Maintain the release links

The page features the **1.4.3** production release. Update the featured version, official APK link, release notes, checksum and screenshot captions together when a later release is ready. Preserve the warning for users migrating from development-signed builds.

Repository topics, social preview recommendations, licensing choices and the first-user checklist are in [the launch plan](../launch-plan.md).
