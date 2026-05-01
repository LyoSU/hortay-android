# Hortay — Play Store screenshots

HTML-based generator for Google Play Store screenshots. One template, four
screens, multi-language via JSON.

## Output

`build/<lang>/01-hero.png` … `04-comments.png` — 1080×1920 PNG, ready to upload.

## Editing

- **Copy (titles/subtitles):** `i18n/<lang>.json`. `\n` in `title` = line break.
- **Which screenshot per screen:** `screens/screens.json` and `screens/photos/`.
- **Visual style:** `src/styles.css` (one file, all four screens share it).
- **Layout/markup:** `src/template.html`.
- **Add a language:** drop `i18n/de.json` with the same keys; `npm run build` picks it up.

## Build

```bash
cd playstore
npm install
npx playwright install chromium
npm run build           # render HTML, then screenshot to PNG
```

`npm run preview` serves the folder on `localhost:4173` so you can iterate
in the browser without re-shooting PNGs.
