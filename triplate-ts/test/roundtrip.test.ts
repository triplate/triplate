import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
// eslint-disable-next-line @typescript-eslint/ban-ts-comment
// @ts-ignore -- sparqljs ships no types we rely on
import sparqljs from 'sparqljs';
import { render } from '../src/index.js';

const parser = new sparqljs.Parser();
const fixturesDir = fileURLToPath(
  new URL('../../triplate.dev/spec/conformance/', import.meta.url),
);

interface Case {
  name: string;
  template: string;
  context: Record<string, never>;
  expected?: string;
  roundtrip?: boolean;
  rawValid?: boolean;
}

for (const file of readdirSync(fixturesDir).filter((f) => f.endsWith('.json'))) {
  const cases: Case[] = JSON.parse(readFileSync(join(fixturesDir, file), 'utf8'));
  const roundtrip = cases.filter((c) => c.roundtrip);
  if (roundtrip.length === 0) continue;
  describe(`${file} round-trip`, () => {
    for (const c of roundtrip) {
      it(`${c.name}: rendered output is valid SPARQL`, () => {
        const rendered = render(c.template, c.context);
        expect(() => parser.parse(rendered)).not.toThrow();
      });
      if (c.template !== c.expected && !c.rawValid) {
        it(`${c.name}: unprocessed template fails to parse (fail-fast)`, () => {
          expect(() => parser.parse(c.template)).toThrow();
        });
      }
    }
  });
}
