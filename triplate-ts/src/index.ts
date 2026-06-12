import { parse } from './parser.js';
import { render as renderData } from './renderer.js';
import { exampleSetToContext, extractPrefixes } from './examples.js';
import type { CompiledTemplateData, ExampleSet, Schema } from './ast.js';

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
}

/** Parses a template once; the result can be rendered many times. */
export function compile(template: string): CompiledTemplate {
  return new CompiledTemplate(parse(template), template);
}

/** One-shot convenience: compile and render in a single call. */
export function render(template: string, context: Context = {}): string {
  return compile(template).render(context);
}
