import { parse } from './parser.js';
import { render as renderData, validateContext } from './renderer.js';
import { exampleSetToContext, extractPrefixes } from './examples.js';
import { TriplateTypeError } from './errors.js';
import type { CompiledTemplateData, ExampleSet, ExampleValue, Schema, TypeExpr } from './ast.js';

export {
  TriplateError,
  TriplateSyntaxError,
  TriplateBindingError,
  TriplateTypeError,
  TriplateCardinalityError,
} from './errors.js';
export { registerType, type CustomSerializer } from './types/index.js';
export type { Schema, ParamDecl, TypeExpr, ExampleSet } from './ast.js';

/** An RDF/JS-style term, accepted by the `term` type. */
export interface Term {
  termType: string;
  value: string;
  language?: string;
  datatype?: { value: string };
}

export type TriplateValue =
  | string
  | number
  | bigint
  | boolean
  | Date
  | Term
  | TriplateValue[]
  | { [key: string]: TriplateValue };

export type Context = Record<string, TriplateValue>;

export class CompiledTemplate {
  /** @internal */
  constructor(
    private readonly data: CompiledTemplateData,
    private readonly source: string,
  ) {}

  /** The declared parameter schema (from the --- frontmatter header). */
  get schema(): Schema {
    return this.data.schema;
  }

  /** The named example sets (from example blocks in the frontmatter). */
  get examples(): ExampleSet[] {
    return this.data.examples;
  }

  /** Render with a caller-supplied context. */
  render(context: Context = {}): string {
    return renderData(this.data, context);
  }

  /** Render using a named example set (for development/preview). */
  previewExample(id: string): string {
    const set = this.data.examples.find((e) => e.id === id);
    if (!set) throw new Error(`no example set with id: ${id}`);
    const context = exampleSetToContext(set, this.data.schema, extractPrefixes(this.source));
    return renderData(this.data, context);
  }

  /**
   * Builds a render context from raw string inputs (e.g. CLI args or editor
   * prompts), coercing each value to its declared scalar type and validating the
   * result against the schema.
   *
   * Per declared parameter: an absent or blank input is omitted (so optional
   * params stay absent); an array param is split on commas (each item trimmed,
   * blanks dropped) and coerced element-wise; a record param cannot be expressed
   * as a string and is skipped — supply records via an example block instead.
   *
   * Throws `TriplateTypeError` if a value cannot be coerced (e.g. a non-numeric
   * `int`), and the usual binding/cardinality errors for structural problems.
   */
  contextFromStrings(inputs: Record<string, string | undefined>): Context {
    const context: Context = {};
    for (const param of this.data.schema.params) {
      const { base, array } = param.type;
      if (base.kind === 'record') continue;
      const raw = inputs[param.name];
      if (raw === undefined || raw.trim() === '') continue;
      context[param.name] = array
        ? raw.split(',').map((s) => s.trim()).filter((s) => s.length > 0).map((s) => coerceScalar(base.kind, s))
        : coerceScalar(base.kind, raw);
    }
    validateContext(this.data.schema, context);
    return context;
  }

  /**
   * The namespace prefixes referenced in the frontmatter — in `example` binding
   * values (`prefix:local`), in literal datatypes on those values (`"x"^^p:t`),
   * and in `literal(p:t)` parameter types.
   *
   * These usages are invisible to a token stream over the body (tools blank the
   * frontmatter before tokenizing), so a linter can use this to avoid flagging a
   * body `PREFIX` declaration as unused. Full `<iri>` values contribute nothing;
   * the empty string denotes the default prefix (`:local`).
   */
  frontmatterPrefixes(): Set<string> {
    const out = new Set<string>();
    for (const set of this.data.examples) {
      for (const ev of Object.values(set.bindings)) collectValuePrefixes(ev, out);
    }
    for (const param of this.data.schema.params) collectTypePrefixes(param.type, out);
    return out;
  }
}

/** Adds the prefix of a datatype reference (`prefix:local`); `<iri>` forms contribute nothing. */
function addDatatypePrefix(dt: string, out: Set<string>): void {
  if (dt.startsWith('<')) return;
  const i = dt.indexOf(':');
  if (i >= 0) out.add(dt.slice(0, i));
}

/** Collects prefixes from an example value, recursing into lists and records. */
function collectValuePrefixes(ev: ExampleValue, out: Set<string>): void {
  switch (ev.kind) {
    case 'pname':
      out.add(ev.prefix);
      break;
    case 'string':
      if (ev.datatype) addDatatypePrefix(ev.datatype, out);
      break;
    case 'list':
      for (const it of ev.items) collectValuePrefixes(it, out);
      break;
    case 'record':
      for (const f of Object.values(ev.fields)) collectValuePrefixes(f, out);
      break;
    // iri, number, bool → no prefix
  }
}

/** Collects literal-datatype prefixes from a declared type, recursing into record fields. */
function collectTypePrefixes(type: TypeExpr, out: Set<string>): void {
  const base = type.base;
  if (base.kind === 'record') {
    for (const ft of Object.values(base.fields)) collectTypePrefixes(ft, out);
  } else if (base.kind === 'literal') {
    addDatatypePrefix(base.datatype, out);
  }
}

/** Coerces a raw string to a typed value for the given scalar kind. */
function coerceScalar(kind: string, raw: string): TriplateValue {
  switch (kind) {
    case 'int': {
      if (!/^[+-]?\d+$/.test(raw)) throw new TriplateTypeError(`invalid int: ${JSON.stringify(raw)}`);
      const n = Number(raw);
      return Number.isSafeInteger(n) ? n : BigInt(raw);
    }
    case 'decimal':
    case 'double': {
      const n = Number(raw);
      if (!Number.isFinite(n)) throw new TriplateTypeError(`invalid ${kind}: ${JSON.stringify(raw)}`);
      return n;
    }
    case 'bool':
      if (raw === 'true') return true;
      if (raw === 'false') return false;
      throw new TriplateTypeError(`invalid bool: ${JSON.stringify(raw)} (expected "true" or "false")`);
    default:
      // iri, pname, string, literal, term, raw, date, dateTime, time, custom → string
      return raw;
  }
}

/** Returns true if `text` opens with a `---` frontmatter header (a Triplate template). */
export function isTemplate(text: string): boolean {
  return /^---[ \t]*(\r?\n|$)/.test(text);
}

/** Parses a template once; the result can be rendered many times. */
export function compile(template: string): CompiledTemplate {
  return new CompiledTemplate(parse(template), template);
}

/** One-shot convenience: compile and render in a single call. */
export function render(template: string, context: Context = {}): string {
  return compile(template).render(context);
}
