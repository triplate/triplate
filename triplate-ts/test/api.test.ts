import { describe, expect, it } from 'vitest';
import {
  compile,
  registerType,
  render,
  symbols,
  TriplateBindingError,
  TriplateSyntaxError,
  TriplateTypeError,
} from '../src/index.js';

const H = (decls: string, body: string) => `---\nparams { ${decls} }\n---\n${body}`;

describe('API behaviour', () => {
  it('compile once, render many', () => {
    const tmpl = compile(H('c: iri', '?s a ${c}'));
    expect(tmpl.render({ c: 'http://example.org/A' })).toContain('<http://example.org/A>');
    expect(tmpl.render({ c: 'http://example.org/B' })).toContain('<http://example.org/B>');
  });

  it('exposes the declared schema', () => {
    const tmpl = compile(H('s: iri, n: int optional', '${s}'));
    expect(tmpl.schema.params.map((p) => p.name)).toEqual(['s', 'n']);
    expect(tmpl.schema.byName.n.optional).toBe(true);
  });

  it('frontmatter is stripped — no leakage before the body', () => {
    const tmpl = compile('---\nparams {\n  c: iri\n}\n\n---\nSELECT * WHERE { ?s a ${c} }');
    expect(tmpl.render({ c: 'http://example.org/A' })).toBe('SELECT * WHERE { ?s a <http://example.org/A> }');
  });

  it('previewExample renders a named example set', () => {
    const tmpl = compile(
      '---\n' +
        'params {\n  classes: iri[]\n}\n' +
        'example demo "Demo" {\n  classes: [ ex:Person, <http://example.org/Org> ]\n}\n' +
        '---\n' +
        'PREFIX ex: <http://example.org/>\n' +
        'SELECT * WHERE {\n{% for c in classes join "UNION" %}\n  { ?s a ${c} }\n{% endfor %}\n}',
    );
    expect(tmpl.examples.map((e) => e.id)).toEqual(['demo']);
    const out = tmpl.previewExample('demo');
    expect(out).toContain('{ ?s a <http://example.org/Person> } UNION { ?s a <http://example.org/Org> }');
  });

  it('bigint serializes as int', () => {
    expect(render(H('x: int', '${x}'), { x: 9007199254740993n })).toBe('9007199254740993');
  });

  it('Date objects serialize for dateTime', () => {
    const d = new Date('2024-03-01T12:00:00.000Z');
    expect(render(H('x: dateTime', '${x}'), { x: d })).toBe(
      '"2024-03-01T12:00:00.000Z"^^<http://www.w3.org/2001/XMLSchema#dateTime>',
    );
  });

  it('RDF/JS terms serialize via the term type', () => {
    const named = { termType: 'NamedNode', value: 'http://example.org/x' };
    expect(render(H('t: term', '${t}'), { t: named })).toBe('<http://example.org/x>');
    const bad = { termType: 'NamedNode', value: 'http://x/> . } DROP ALL #' };
    expect(() => render(H('t: term', '${t}'), { t: bad })).toThrow(TriplateTypeError);
  });

  it('custom types can be registered (extensibility)', () => {
    registerType('uuidref', (value, pos) => {
      if (typeof value !== 'string' || !/^[0-9a-f-]{36}$/.test(value)) {
        throw new TriplateTypeError('invalid uuid', pos.line, pos.column);
      }
      return `<urn:uuid:${value}>`;
    });
    expect(render(H('id: uuidref', '${id}'), { id: '123e4567-e89b-12d3-a456-426614174000' })).toBe(
      '<urn:uuid:123e4567-e89b-12d3-a456-426614174000>',
    );
  });

  it('IRI templates percent-encode holes and validate the result', () => {
    expect(render(H('id: string', '$<http://ex.org/${id}>'), { id: 'a/b é' })).toBe(
      '<http://ex.org/a%2Fb%20%C3%A9>',
    );
    expect(() => render(H('x: raw', '$<http://ex.org/${x}>'), { x: 'a> <b' })).toThrow(TriplateTypeError);
  });

  it('explicit join is verbatim, default join pads', () => {
    const body = '{% for c in xs join "," %}${c}{% endfor %}';
    expect(render(H('xs: string[]', body), { xs: ['a', 'b'] })).toBe('"a" , "b"');
    const ebody = '{% for c in xs join "," explicit %}${c}{% endfor %}';
    expect(render(H('xs: string[]', ebody), { xs: ['a', 'b'] })).toBe('"a","b"');
  });

  it('frontmatterPrefixes recovers prefixes from example values and literal types', () => {
    const tmpl = compile(
      '---\n' +
        'params {\n  type: iri\n  amount: literal(xsd:decimal)\n  note: string\n}\n' +
        'example demo "Demo" {\n  type: schema:Person\n  amount: "5"\n  note: "n"^^my:dt\n}\n' +
        '---\n${type}',
    );
    expect([...tmpl.frontmatterPrefixes()].sort()).toEqual(['my', 'schema', 'xsd']);
  });

  it('frontmatterPrefixes ignores full <iri> values (no prefix)', () => {
    const tmpl = compile(
      '---\nparams {\n  type: iri\n}\nexample demo "D" {\n  type: <http://example.org/Person>\n}\n---\n${type}',
    );
    expect(tmpl.frontmatterPrefixes().size).toBe(0);
  });

  it('undeclared variable is a compile-time error', () => {
    expect(() => compile(H('s: iri', '${t}'))).toThrow(TriplateSyntaxError);
  });

  it('missing required parameter throws at render', () => {
    expect(() => render(H('s: iri', '${s}'), {})).toThrow(TriplateBindingError);
  });

  it('a template without frontmatter is rejected', () => {
    expect(() => compile('SELECT * WHERE { ?s ?p ?o }')).toThrow(TriplateSyntaxError);
  });

  describe('positioned symbols', () => {
    const SRC =
      '---\n' +
      'params {\n  who: pname\n  amount: literal(xsd:decimal)\n}\n' +
      'example demo "D" {\n  who: schema:Person\n  amount: "9.99"^^xsd:decimal\n  home: <http://ex.org/me>\n}\n' +
      '---\n' +
      '?s a ${who} . {% if amount %}${amount}{% endif %}';

    it('every symbol span slices its own text from the source', () => {
      for (const s of compile(SRC).symbols()) {
        const slice = SRC.slice(s.start, s.end);
        const expected =
          s.kind === 'pname'
            ? `${s.prefix}:${s.local}`
            : s.kind === 'iri'
              ? `<${s.value}>`
              : s.kind === 'literal'
                ? JSON.stringify(s.value)
                : s.name;
        expect(slice).toBe(expected);
      }
    });

    it('captures decls, refs, binding keys, pnames, iris and literals', () => {
      const byKind = (k: string) => compile(SRC).symbols().filter((s) => s.kind === k);
      expect(byKind('paramDecl').map((s) => (s as { name: string }).name)).toEqual(['who', 'amount']);
      // Two `amount` refs: the {% if amount %} condition and the ${amount} hole.
      expect(byKind('paramRef').map((s) => (s as { name: string }).name)).toEqual(['who', 'amount', 'amount']);
      expect(byKind('bindingKey').map((s) => (s as { name: string }).name)).toEqual(['who', 'amount', 'home']);
      // pname: the literal(xsd:decimal) type, the schema:Person value, and the ^^xsd:decimal datatype.
      expect(byKind('pname')).toHaveLength(3);
      expect(byKind('iri').map((s) => (s as { value: string }).value)).toEqual(['http://ex.org/me']);
      const lits = byKind('literal') as Array<{ value: string; datatype?: string }>;
      expect(lits).toEqual([{ kind: 'literal', value: '9.99', datatype: 'xsd:decimal', start: expect.any(Number), end: expect.any(Number) }]);
    });

    it('symbols are emitted in ascending source order', () => {
      const offsets = compile(SRC).symbols().map((s) => s.start);
      expect(offsets).toEqual([...offsets].sort((a, b) => a - b));
    });

    it('grouping paramDecl + paramRef + bindingKey by name yields every rename site', () => {
      const sites = compile(SRC)
        .symbols()
        .filter((s) => (s.kind === 'paramDecl' || s.kind === 'paramRef' || s.kind === 'bindingKey') && (s as { name: string }).name === 'amount');
      expect(sites.map((s) => s.kind)).toEqual(['paramDecl', 'bindingKey', 'paramRef', 'paramRef']);
    });

    it('`#` is plain text in the body, not a comment — directives after it are live', () => {
      const tmpl = compile('---\nparams { title: raw }\n---\n# ${title}');
      expect(tmpl.render({ title: 'My Title' })).toBe('# My Title');
    });

    it('`#` in the frontmatter is a syntax error, not a comment', () => {
      const src = '---\nparams { who: pname }\n# a comment\n---\n?s a ${who}';
      expect(() => compile(src)).toThrow(TriplateSyntaxError);
    });

    it('the standalone symbols() is lenient — returns what parsed on a malformed template', () => {
      const bad = '---\nparams { a: int }\nexample x {\n  who: schema:Person\n';
      expect(() => compile(bad)).toThrow(TriplateSyntaxError);
      expect(symbols(bad).map((s) => s.kind)).toEqual(['paramDecl', 'bindingKey', 'pname']);
    });

    describe('loop variables', () => {
      it('a for loop emits a loopDecl at the item plus a loopRef per body ref, all one scope', () => {
        const src = '---\nparams { graphIris: pname[] }\n---\n{% for g in graphIris %} FROM ${g} ${g} {% endfor %}';
        const syms = symbols(src);
        // The for source is an ordinary parameter reference, not a loop ref.
        const srcRef = syms.find((s) => s.kind === 'paramRef') as { name: string } | undefined;
        expect(srcRef?.name).toBe('graphIris');
        const decls = syms.filter((s) => s.kind === 'loopDecl') as Array<{ name: string; scope: number }>;
        const refs = syms.filter((s) => s.kind === 'loopRef') as Array<{ name: string; scope: number }>;
        expect(decls.map((d) => d.name)).toEqual(['g']);
        expect(refs.map((r) => r.name)).toEqual(['g', 'g']);
        // Declaration and both references share the one scope id.
        expect(new Set([decls[0].scope, ...refs.map((r) => r.scope)]).size).toBe(1);
        // Spans still ascend (the item precedes the source in the header).
        const offsets = syms.map((s) => s.start);
        expect(offsets).toEqual([...offsets].sort((a, b) => a - b));
      });

      it('every loop symbol span slices its own text', () => {
        const src = '---\nparams { xs: pname[] }\n---\n{% for g in xs %}${g}{% endfor %}';
        for (const s of symbols(src)) {
          if (s.kind === 'loopDecl' || s.kind === 'loopRef') {
            expect(src.slice(s.start, s.end)).toBe(s.name);
          }
        }
      });

      it('shadowing — inner refs bind to the inner scope, outer refs to the outer', () => {
        const src = '---\nparams { a: pname[]\n  b: pname[] }\n---\n{% for g in a %}${g}{% for g in b %}${g}{% endfor %}${g}{% endfor %}';
        const syms = symbols(src);
        const decls = syms.filter((s) => s.kind === 'loopDecl') as Array<{ scope: number }>;
        const refs = syms.filter((s) => s.kind === 'loopRef') as Array<{ scope: number }>;
        expect(decls).toHaveLength(2);
        const [outer, inner] = [decls[0].scope, decls[1].scope];
        expect(outer).not.toBe(inner);
        // Refs in source order: outer ${g}, inner ${g}, outer ${g}.
        expect(refs.map((r) => r.scope)).toEqual([outer, inner, outer]);
      });

      it('a loop variable shadowing a same-named parameter — only in-scope refs become loopRef', () => {
        const src = '---\nparams { g: pname\n  xs: pname[] }\n---\n${g} {% for g in xs %}${g}{% endfor %} ${g}';
        const syms = symbols(src);
        // Two ${g} outside the loop stay paramRefs; the in-loop ${g} is a loopRef.
        expect((syms.filter((s) => s.kind === 'paramRef') as Array<{ name: string }>).map((s) => s.name)).toEqual(['g', 'xs', 'g']);
        const refs = syms.filter((s) => s.kind === 'loopRef') as Array<{ name: string; scope: number }>;
        const decl = syms.find((s) => s.kind === 'loopDecl') as { scope: number };
        expect(refs.map((r) => r.name)).toEqual(['g']);
        expect(refs[0].scope).toBe(decl.scope);
      });

      it('a dotted loop reference marks only the root segment', () => {
        const src = '---\nparams { xs: pname[] }\n---\n{% for g in xs %}${g.field}{% endfor %}';
        const refs = symbols(src).filter((s) => s.kind === 'loopRef') as Array<{ name: string }>;
        expect(refs.map((r) => r.name)).toEqual(['g']);
      });

      it('lenient mode still collects loop symbols from a malformed template', () => {
        const bad = '---\nparams { xs: pname[] }\n---\n{% for g in xs %}${g} {% if';
        const kinds = symbols(bad).map((s) => s.kind);
        expect(kinds).toContain('loopDecl');
        expect(kinds).toContain('loopRef');
      });
    });
  });
});
