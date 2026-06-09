import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const configPath = resolve(dirname(fileURLToPath(import.meta.url)), '../vite.config.ts');
const configSource = readFileSync(configPath, 'utf8');

describe('AI Gateway Vite config', () => {
  it('keeps the backend static base for production builds', () => {
    expect(configSource).toContain("base: command === 'build' ? '/api/plugins/airelay/ui/' : '/'");
  });

  it('proxies API calls to the backend during dev hot reload', () => {
    expect(configSource).toContain("'/api'");
    expect(configSource).toContain("target: 'http://localhost:8080'");
    expect(configSource).toContain('changeOrigin: true');
  });
});
