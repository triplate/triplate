import type { ExampleSet, ExampleValue, Schema, TypeExpr } from './ast.js';
import { TriplateError } from './errors.js';

const PREFIX_RE = /(?:PREFIX|@prefix)\s+([A-Za-z_][\w.-]*)?\s*:\s*<([^>]*)>/gi;

/** Extract prefix → namespace IRI from a template's PREFIX / @prefix declarations. */
export function extractPrefixes(template: string): Record<string, string> {
  const out: Record<string, string> = {};

  for (const match of template.matchAll(PREFIX_RE)) {
    out[match[1] ?? ''] = match[2];
  }

  return out;
}

/** Convert an example set into a render context, resolving prefixed names. */
export function exampleSetToContext(set: ExampleSet, schema: Schema, prefixes: Record<string, string>): Record<string, unknown> {
  const context: Record<string, unknown> = {};

  for (const [name, value] of Object.entries(set.bindings)) {
    const type = schema.byName[name];

    if (!type) {
      throw new TriplateError(`example "${set.id}" binds unknown parameter: ${name}`);
    }

    context[name] = convert(value, type, prefixes, set.id);
  }
  return context;
}

function convert(value: ExampleValue, type: TypeExpr, prefixes: Record<string, string>, id: string): unknown {
  if (type.array) {
    if (value.kind !== 'list') {
      throw new TriplateError(`example "${id}": expected a list`);
    }

    const element: TypeExpr = { base: type.base, array: false, optional: false };

    return value.items.map((it) => convert(it, element, prefixes, id));
  }

  if (type.base.kind === 'record') {
    if (value.kind !== 'record') {
      throw new TriplateError(`example "${id}": expected a record`);
    }

    const out: Record<string, unknown> = {};

    for (const [f, ft] of Object.entries(type.base.fields)) {
      if (f in value.fields) out[f] = convert(value.fields[f], ft, prefixes, id);
    }

    return out;
  }

  const scalar = type.base.kind;

  switch (value.kind) {
    case 'iri':
      return value.value;
    case 'pname': {
      if (scalar === 'pname') {
        return `${value.prefix}:${value.local}`;
      }

      const namespaceIri = prefixes[value.prefix];

      if (namespaceIri === undefined) {
        throw new TriplateError(`example "${id}": unknown prefix '${value.prefix}:'`);
      }

      return namespaceIri + value.local;
    }
    case 'string':
      return value.value;
    case 'number':
      return value.value;
    case 'bool':
      return value.value;
    default:
      throw new TriplateError(`example "${id}": value does not match declared type`);
  }
}
