import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sampleRoot = resolve(appRoot, '../../..');
const legacyRoot = resolve(appRoot, 'src/legacy');
const sourceRoot = resolve(sampleRoot, 'src/main/resources/ui/ai-gateway-ui');

function listFiles(root: string) {
  const files: string[] = [];
  const visit = (dir: string) => {
    for (const entry of readdirSync(dir)) {
      const path = resolve(dir, entry);
      if (statSync(path).isDirectory()) visit(path);
      else files.push(relative(root, path));
    }
  };
  visit(root);
  return files.sort();
}

describe('AI Gateway legacy UI migration entry', () => {
  it('keeps the Vite entry wired to the full legacy custom element app', () => {
    const html = readFileSync(resolve(appRoot, 'index.html'), 'utf8');

    expect(html).toContain('<ai-proxy-app></ai-proxy-app>');
    expect(html).toContain('/src/legacy/css/style.css');
    expect(html).toContain('/src/legacy/js/app.js');
  });

  it('keeps migrated legacy JS and CSS in parity with the existing backend static UI', () => {
    const sourceFiles = listFiles(sourceRoot).filter((file) => file.startsWith('js/') || file.startsWith('css/'));
    const legacyFiles = listFiles(legacyRoot).filter((file) => !file.endsWith('.test.ts'));

    expect(legacyFiles).toEqual(sourceFiles);
    for (const file of sourceFiles) {
      expect(readFileSync(resolve(legacyRoot, file), 'utf8')).toEqual(readFileSync(resolve(sourceRoot, file), 'utf8'));
    }
  });
});
