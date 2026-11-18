# Forge landing page

Static promotional page for GitHub Pages. It uses the existing application screenshots and does not change Android code, APKs or signing.

## Preview

From the repository root, serve only the public documentation folder:

```text
python -m http.server 8765 --bind 127.0.0.1 --directory docs
```

Open `http://127.0.0.1:8765/`. Stop the server with Ctrl+C. The page can also be opened directly from `docs/index.html`.

The page uses plain HTML/CSS, the existing Forge icon path/colors, system fonts and the original `docs/screenshots/*.png` files. It has no JavaScript, package dependencies, forms, analytics, cookies or remote fonts. The genuine screenshots remain unchanged and are labeled as version 1.3.0 synthetic-data captures, with training/nutrition layouts retained in 1.4.2.

## GitHub Pages, after presentation approval

After the owner approves and pushes these files, choose **Settings → Pages → Build and deployment → Deploy from a branch → main → /docs → Save**. `docs/index.html` is the entry point and `.nojekyll` keeps the site static. No additional deployment workflow is required. Images use relative paths that also work under a repository Pages URL.

Once GitHub confirms the deployed URL, verify it before adding it to the repository's About website field. The default project URL is `https://ritikverma098.github.io/Forge-Fitness-Tracker/`. A custom domain is optional and unconfigured.

GitHub Pages serves the `/docs` folder, including its public documentation. Keep signing files, personal records and private configuration outside it and outside the repository.

## Maintain the release links

The page deliberately links to the verified **1.4.2** production release, rather than guessing a future APK URL. When a later compatible release is verified, update the featured version, APK size, release links and checksum link together. Preserve the warning for users migrating from development-signed builds. No adoption counts, testimonials, ratings or Play Store availability are claimed.

Repository topics, social preview recommendations, licensing choices and the first-user checklist are in [the launch plan](../launch-plan.md).
