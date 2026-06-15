import { describe, expect, it } from 'vitest';
import {
  compile,
  registerType,
  render,
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
    const tmpl = compile('---\nparams {\n  c: iri\n}\n# a metadata comment\n---\nSELECT * WHERE { ?s a ${c} }');
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
});
