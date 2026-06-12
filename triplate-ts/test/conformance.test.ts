import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { render, TriplateError } from '../src/index.js';

const fixturesDir = fileURLToPath(
  new URL('../../triplate.dev/spec/conformance/', import.meta.url),
);

interface Case {
  name: string;
  template: string;
  context: Record<string, never>;
  expected?: string;
  error?: string;
  roundtrip?: boolean;
}

for (const file of readdirSync(fixturesDir).filter((f) => f.endsWith('.json'))) {
  const cases: Case[] = JSON.parse(readFileSync(join(fixturesDir, file), 'utf8'));
  describe(file, () => {
    for (const c of cases) {
      it(c.name, () => {
        if (c.error) {
          try {
            render(c.template, c.context);
            expect.fail(`expected ${c.error} to be thrown`);
          } catch (e) {
            if (!(e instanceof TriplateError)) throw e;
            expect(e.name).toBe(c.error);
          }
        } else {
          expect(render(c.template, c.context)).toBe(c.expected);
        }
      });
    }
  });
}
