/** A dotted reference path, e.g. ["p", "id"] for ${p.id}. */
export type RefPath = string[];

/** Scalar serialization type (how a value is emitted). */
export type ScalarType =
  | { kind: 'iri' }
  | { kind: 'pname' }
  | { kind: 'string' }
  | { kind: 'int' }
  | { kind: 'decimal' }
  | { kind: 'double' }
  | { kind: 'bool' }
  | { kind: 'date' }
  | { kind: 'dateTime' }
  | { kind: 'time' }
  | { kind: 'literal'; datatype: string }
  | { kind: 'term' }
  | { kind: 'raw' }
  | { kind: 'custom'; name: string };

export type TypeBase = ScalarType | { kind: 'record'; fields: Record<string, TypeExpr> };

/** A declared type: a scalar/record base, optionally an array, optionally optional, with bounds. */
export interface TypeExpr {
  base: TypeBase;
  array: boolean;
  optional: boolean;
  min?: number;
  max?: number;
}

export interface ParamDecl {
  name: string;
  type: TypeExpr;
}

export interface Schema {
  params: ParamDecl[];
  byName: Record<string, TypeExpr>;
}

/** A language tag: `@en` static, or `@${lang}` dynamic. */
export type LangSpec = { static: string } | { path: RefPath };

/** A part of an interpolated string or IRI template. */
export type Part = { text: string } | { path: RefPath; line: number; column: number };

export interface TextNode {
  type: 'text';
  value: string;
}

export interface ValueNode {
  type: 'value';
  path: RefPath;
  spread?: boolean;
  join?: string;
  joinExact?: boolean;
  line: number;
  column: number;
}

export interface InterpNode {
  type: 'interp';
  parts: Part[];
  lang?: LangSpec;
  datatype?: string;
  line: number;
  column: number;
}

export interface IriNode {
  type: 'iri';
  parts: Part[];
  line: number;
  column: number;
}

export interface ForNode {
  type: 'for';
  item: string;
  source: RefPath;
  join?: string;
  joinExact?: boolean;
  body: Node[];
  line: number;
  column: number;
}

export interface Cond {
  negated: boolean;
  path: RefPath;
  line: number;
  column: number;
}

export interface IfNode {
  type: 'if';
  branches: { cond: Cond; body: Node[] }[];
  elseBody?: Node[];
  line: number;
  column: number;
}

export type Node = TextNode | ValueNode | InterpNode | IriNode | ForNode | IfNode;

/** An example value literal (RDF term syntax), resolved to host values at preview. */
export type ExampleValue =
  | { kind: 'iri'; value: string }
  | { kind: 'pname'; prefix: string; local: string }
  | { kind: 'string'; value: string; lang?: string; datatype?: string }
  | { kind: 'number'; value: number }
  | { kind: 'bool'; value: boolean }
  | { kind: 'list'; items: ExampleValue[] }
  | { kind: 'record'; fields: Record<string, ExampleValue> };

export interface ExampleSet {
  id: string;
  description?: string;
  bindings: Record<string, ExampleValue>;
  line: number;
  column: number;
}

/**
 * A positioned source symbol — a flat, non-opaque view over the frontmatter and
 * body references that drive IDE features (tooltips, prefix rename, parameter
 * rename). Offsets are absolute, 0-based and end-exclusive UTF-16 code-unit
 * indices into the original source (the same units VS Code's `positionAt`/
 * `offsetAt` use).
 *
 * `paramDecl` (frontmatter `params`), `paramRef` (every body `${…}`/`{% … %}`
 * root reference) and `bindingKey` (frontmatter `example` keys) share a name
 * space: grouping by `name` yields every rename site of a parameter.
 * `pname`/`iri`/`literal`/`comment` only ever occur in the frontmatter and feed
 * the overlay (tooltips, prefix rename) and formatters. A `comment`'s `value` is
 * the raw source slice (the leading `#` through the end of the line), so
 * `source.slice(start, end) === value`.
 */
export type TemplateSymbol =
  | { kind: 'paramDecl'; name: string; start: number; end: number }
  | { kind: 'paramRef'; name: string; start: number; end: number }
  | { kind: 'bindingKey'; name: string; start: number; end: number }
  | { kind: 'pname'; prefix: string; local: string; start: number; end: number }
  | { kind: 'iri'; value: string; start: number; end: number }
  | { kind: 'literal'; value: string; datatype?: string; start: number; end: number }
  | { kind: 'comment'; value: string; start: number; end: number };

export interface CompiledTemplateData {
  schema: Schema;
  examples: ExampleSet[];
  body: Node[];
  symbols: TemplateSymbol[];
}
