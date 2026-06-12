import type { ExampleSet, ExampleValue, Schema, TypeExpr } from './ast.js';
import { TriplateError } from './errors.js';

const PREFIX_RE = /(?:PREFIX|@prefix)\s+([A-Za-z_][\w.-]*)?\s*:\s*<([^>]*)>/gi;

/** Extract prefix → namespace IRI from a template's PREFIX / @prefix declarations. */
export function extractPrefixes(template: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const m of template.matchAll(PREFIX_RE)) out[m[1] ?? ''] = m[2];
  return out;
}

/** Convert an example set into a render context, resolving prefixed names. */
export function exampleSetToContext(
  set: ExampleSet,
  schema: Schema,
  prefixes: Record<string, string>,
): Record<string, unknown> {
  const ctx: Record<string, unknown> = {};
  for (const [name, ev] of Object.entries(set.bindings)) {
    const type = schema.byName[name];
    if (!type) throw new TriplateError(`example "${set.id}" binds unknown parameter: ${name}`);
    ctx[name] = convert(ev, type, prefixes, set.id);
  }
  return ctx;
}

function convert(ev: ExampleValue, type: TypeExpr, prefixes: Record<string, string>, id: string): unknown {
  if (type.array) {
    if (ev.kind !== 'list') throw new TriplateError(`example "${id}": expected a list`);
    const elem: TypeExpr = { base: type.base, array: false, optional: false };
    return ev.items.map((it) => convert(it, elem, prefixes, id));
  }
  if (type.base.kind === 'record') {
    if (ev.kind !== 'record') throw new TriplateError(`example "${id}": expected a record`);
    const out: Record<string, unknown> = {};
    for (const [f, ft] of Object.entries(type.base.fields)) {
      if (f in ev.fields) out[f] = convert(ev.fields[f], ft, prefixes, id);
    }
    return out;
  }
  const scalar = type.base.kind;
  switch (ev.kind) {
    case 'iri':
      return ev.value;
    case 'pname': {
      if (scalar === 'pname') return `${ev.prefix}:${ev.local}`;
      const ns = prefixes[ev.prefix];
      if (ns === undefined) throw new TriplateError(`example "${id}": unknown prefix '${ev.prefix}:'`);
      return ns + ev.local;
    }
    case 'string':
      return ev.value;
    case 'number':
      return ev.value;
    case 'bool':
      return ev.value;
    default:
      throw new TriplateError(`example "${id}": value does not match declared type`);
  }
}
