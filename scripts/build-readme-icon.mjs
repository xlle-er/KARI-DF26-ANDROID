// Use the official transparent rounded export; GitHub strips CSS border-radius.
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
const root = new URL('../', import.meta.url);
const rounded = fs.readFileSync(new URL('branding/icon-rounded.png', root));
fs.writeFileSync(new URL('docs/icon-rounded.png', root), rounded);
const output = new URL('docs/icon-rounded.svg', root);
fs.copyFileSync(new URL('branding/icon-rounded.svg',root),output);
console.log(`Wrote ${fileURLToPath(output)}`);
