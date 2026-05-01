/*
 * Render: i18n JSON × screens.json → build/<lang>/<id>.html
 * Reads:  i18n/*.json, screens/screens.json, src/template.html
 * Writes: build/<lang>/<id>.html (one HTML per language × screen)
 */

import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..');

const escapeHtml = (s) => s
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;');

const titleHtml = (s) => escapeHtml(s).replace(/\n/g, '<br>');
const titlePlain = (s) => s.replace(/\n/g, ' ');

async function main() {
  const screens = JSON.parse(
    await fs.readFile(path.join(root, 'screens/screens.json'), 'utf8')
  );
  const template = await fs.readFile(
    path.join(root, 'src/template.html'),
    'utf8'
  );

  const langFiles = (await fs.readdir(path.join(root, 'i18n')))
    .filter((f) => f.endsWith('.json'));

  let count = 0;
  for (const langFile of langFiles) {
    const lang = langFile.replace('.json', '');
    const dict = JSON.parse(
      await fs.readFile(path.join(root, 'i18n', langFile), 'utf8')
    );
    const outDir = path.join(root, 'build', lang);
    await fs.mkdir(outDir, { recursive: true });

    for (const screen of screens) {
      const t = dict[screen.key];
      if (!t || !t.title || !t.subtitle) {
        console.warn(`  ! ${lang}: missing copy for "${screen.key}", skipping`);
        continue;
      }
      const html = template
        .replaceAll('{{lang}}', lang)
        .replaceAll('{{id}}', screen.id)
        .replaceAll('{{photo}}', screen.photo)
        .replaceAll('{{title_plain}}', escapeHtml(titlePlain(t.title)))
        .replaceAll('{{title}}', titleHtml(t.title))
        .replaceAll('{{subtitle}}', escapeHtml(t.subtitle));

      await fs.writeFile(path.join(outDir, `${screen.id}.html`), html);
      count++;
    }
    console.log(`  ✓ ${lang}/  (${screens.length} screens)`);
  }
  console.log(`Rendered ${count} HTML file(s) into build/`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
