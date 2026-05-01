/*
 * Capture: build/<lang>/*.html → build/<lang>/*.png  (1080×1920, deviceScaleFactor=1).
 * Google Play wants a real 1080×1920 PNG; do NOT enable deviceScaleFactor>1
 * — that produces 2160×3840 and Play's uploader downscales lossy.
 */

import { chromium } from 'playwright';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..');
const buildDir = path.join(root, 'build');

async function listLangDirs() {
  const entries = await fs.readdir(buildDir, { withFileTypes: true });
  return entries.filter((e) => e.isDirectory()).map((e) => e.name);
}

async function main() {
  const langs = await listLangDirs();
  if (langs.length === 0) {
    console.error('No build/<lang>/ directories. Run `npm run render` first.');
    process.exit(1);
  }

  // feature.html is 1024×500 (Play Store feature graphic); rest are 1080×1920.
  const viewportFor = (filename) =>
    filename === 'feature.html'
      ? { width: 1024, height: 500 }
      : { width: 1080, height: 1920 };

  const browser = await chromium.launch();
  const context = await browser.newContext({
    viewport: { width: 1080, height: 1920 },
    deviceScaleFactor: 1,
  });
  const page = await context.newPage();

  let total = 0;
  for (const lang of langs) {
    const langDir = path.join(buildDir, lang);
    const files = (await fs.readdir(langDir)).filter((f) => f.endsWith('.html'));
    for (const f of files) {
      await page.setViewportSize(viewportFor(f));
      const url = 'file://' + path.join(langDir, f);
      await page.goto(url, { waitUntil: 'networkidle' });
      // Small extra wait so Google Fonts paint pass settles.
      await page.waitForTimeout(250);
      const out = path.join(langDir, f.replace('.html', '.png'));
      await page.screenshot({ path: out, omitBackground: false, type: 'png' });
      console.log(`  ✓ ${lang}/${path.basename(out)}`);
      total++;
    }
  }

  await browser.close();
  console.log(`Captured ${total} PNG file(s).`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
