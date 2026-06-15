import {
  TriplateBindingError,
  TriplateCardinalityError,
  TriplateTypeError,
} from './errors.js';
import type {
  CompiledTemplateData,
  Cond,
  IfNode,
  LangSpec,
  Node,
  Part,
  RefPath,
  ScalarType,
  Schema,
  TypeExpr,
} from './ast.js';
import {
  escapeString,
  getCustomType,
  percentEncode,
  requireString,
  serializeBoolean,
  serializeDate,
  serializeDateTime,
  serializeDecimal,
  serializeDouble,
  serializeInt,
  serializeIri,
  serializePname,
  serializeRaw,
  serializeString,
  serializeTerm,
  serializeTime,
  serializeTypedLiteral,
  validateAssembledIri,
  validateIri,
  validateLangTag,
  type Pos,
} from './types/index.js';

type Scope = Record<string, unknown>;
interface Bound {
  value: unknown;
  type: TypeExpr;
}
interface Env {
  schema: Schema;
  context: Scope;
  loop: Record<string, Bound>[];
}

export function render(data: CompiledTemplateData, context: Scope): string {
  validateContext(data.schema, context);
  return renderNodes(data.body, { schema: data.schema, context, loop: [] });
}

// ---- context validation (up front) --------------------------------------

export function validateContext(schema: Schema, context: Scope): void {
  for (const key of Object.keys(context)) {
    if (!(key in schema.byName)) {
      throw new TriplateBindingError(`unknown parameter: ${key}`);
    }
  }
  for (const { name, type } of schema.params) {
    const present = Object.prototype.hasOwnProperty.call(context, name);
    if (!present) {
      if (!type.optional) throw new TriplateBindingError(`missing required parameter: ${name}`);
      continue;
    }
    validateValue(type, context[name], name);
  }
}

function validateValue(type: TypeExpr, value: unknown, label: string): void {
  if (type.array) {
    if (!Array.isArray(value)) throw new TriplateTypeError(`${label} must be a list`);
    if (type.min !== undefined && value.length < type.min) {
      throw new TriplateCardinalityError(`${label} requires at least ${type.min} item(s), got ${value.length}`);
    }
    if (type.max !== undefined && value.length > type.max) {
      throw new TriplateCardinalityError(`${label} allows at most ${type.max} item(s), got ${value.length}`);
    }
    const elem: TypeExpr = { base: type.base, array: false, optional: false };
    value.forEach((el, i) => validateValue(elem, el, `${label}[${i}]`));
  } else if (type.base.kind === 'record') {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) {
      throw new TriplateTypeError(`${label} must be an object`);
    }
    const obj = value as Record<string, unknown>;
    for (const key of Object.keys(obj)) {
      if (!(key in type.base.fields)) throw new TriplateBindingError(`unknown field: ${label}.${key}`);
    }
    for (const [fname, ftype] of Object.entries(type.base.fields)) {
      const fpresent = Object.prototype.hasOwnProperty.call(obj, fname);
      if (!fpresent) {
        if (!ftype.optional) throw new TriplateBindingError(`missing field: ${label}.${fname}`);
        continue;
      }
      validateValue(ftype, obj[fname], `${label}.${fname}`);
    }
  } else if (Array.isArray(value)) {
    throw new TriplateTypeError(`${label} must be a scalar, got a list`);
  }
}

// ---- resolution ----------------------------------------------------------

function resolve(env: Env, path: RefPath, pos: Pos, mustExist: boolean): { value: unknown; type: TypeExpr; present: boolean } {
  const head = path[0];
  let type: TypeExpr;
  let value: unknown;
  let present: boolean;
  let found = false;
  for (let i = env.loop.length - 1; i >= 0; i--) {
    if (Object.prototype.hasOwnProperty.call(env.loop[i], head)) {
      ({ value, type } = env.loop[i][head]);
      present = true;
      found = true;
      break;
    }
  }
  if (!found) {
    type = env.schema.byName[head];
    if (!type) throw new TriplateBindingError(`no binding for variable: ${head}`, pos.line, pos.column);
    present = Object.prototype.hasOwnProperty.call(env.context, head);
    value = present ? env.context[head] : undefined;
  }
  for (let i = 1; i < path.length; i++) {
    const field = path[i];
    if (type!.base.kind !== 'record') {
      throw new TriplateTypeError(`${path.slice(0, i).join('.')} is not an object`, pos.line, pos.column);
    }
    const ft: TypeExpr | undefined = type!.base.fields[field];
    if (!ft) throw new TriplateBindingError(`unknown field: ${path.slice(0, i + 1).join('.')}`, pos.line, pos.column);
    if (present! && value !== null && typeof value === 'object' && !Array.isArray(value) && Object.prototype.hasOwnProperty.call(value, field)) {
      value = (value as Record<string, unknown>)[field];
    } else {
      present = false;
      value = undefined;
    }
    type = ft;
  }
  if (mustExist && !present!) {
    throw new TriplateBindingError(`no value for ${path.join('.')} (an optional value? guard it with {% if %})`, pos.line, pos.column);
  }
  return { value, type: type!, present: present! };
}

function scalarOf(type: TypeExpr, path: RefPath, pos: Pos): ScalarType {
  if (type.array) throw new TriplateTypeError(`${path.join('.')} is a list; loop over it with {% for %}`, pos.line, pos.column);
  if (type.base.kind === 'record') throw new TriplateTypeError(`${path.join('.')} is an object; reference a field`, pos.line, pos.column);
  return type.base;
}

// ---- rendering -----------------------------------------------------------

function renderNodes(nodes: Node[], env: Env): string {
  let out = '';
  for (const node of nodes) {
    switch (node.type) {
      case 'text':
        out += node.value;
        break;
      case 'value': {
        out += node.spread ? renderSpread(env, node) : renderValue(env, node);
        break;
      }
      case 'interp':
        out += renderInterp(env, node);
        break;
      case 'iri':
        out += renderIri(env, node);
        break;
      case 'for':
        out += renderFor(env, node);
        break;
      case 'if':
        out += renderIf(env, node);
        break;
    }
  }
  return out;
}

function renderValue(env: Env, node: Extract<Node, { type: 'value' }>): string {
  const { value, type } = resolve(env, node.path, node, true);
  return serializeScalar(scalarOf(type, node.path, node), value, node);
}

function renderSpread(env: Env, node: Extract<Node, { type: 'value' }>): string {
  const { value, type } = resolve(env, node.path, node, true);
  if (!type.array || !Array.isArray(value)) {
    throw new TriplateTypeError(`${node.path.join('.')} is not a list`, node.line, node.column);
  }
  const elemType: TypeExpr = { base: type.base, array: false, optional: false };
  const t = scalarOf(elemType, node.path, node);
  const chunks = value.map((item) => serializeScalar(t, item, node));
  return joinChunks(chunks, node.join, node.joinExact, ' ');
}

/** Join rendered chunks with a `join "<sep>" [explicit]` clause, trimming boundary whitespace. */
function joinChunks(chunks: string[], join: string | undefined, joinExact: boolean | undefined, defaultSep: string): string {
  let separator: string;
  if (join === undefined) separator = defaultSep;
  else if (joinExact) separator = join;
  else {
    const trimmed = join.trim();
    separator = trimmed ? ` ${trimmed} ` : ' ';
  }
  if (separator === '') return chunks.join('');
  let out = chunks[0] ?? '';
  for (let i = 1; i < chunks.length; i++) {
    out = out.replace(/\s+$/, '') + separator + chunks[i].replace(/^\s+/, '');
  }
  return out;
}

function serializeScalar(t: ScalarType, value: unknown, pos: Pos): string {
  switch (t.kind) {
    case 'iri': return serializeIri(value, pos);
    case 'pname': return serializePname(value, pos);
    case 'string': return serializeString(value, pos);
    case 'int': return serializeInt(value, pos);
    case 'decimal': return serializeDecimal(value, pos);
    case 'double': return serializeDouble(value, pos);
    case 'bool': return serializeBoolean(value, pos);
    case 'date': return serializeDate(value, pos);
    case 'dateTime': return serializeDateTime(value, pos);
    case 'time': return serializeTime(value, pos);
    case 'literal': return serializeTypedLiteral(value, t.datatype, pos);
    case 'term': return serializeTerm(value, pos);
    case 'raw': return serializeRaw(value, pos);
    case 'custom': {
      const fn = getCustomType(t.name);
      if (!fn) throw new TriplateTypeError(`unknown custom type: ${t.name}`, pos.line, pos.column);
      return fn(value, pos);
    }
  }
}

/** Lexical form of a value inside a string/IRI construct. `raw` = insert verbatim. */
function holeLexical(env: Env, part: Extract<Part, { path: RefPath }>): { raw: boolean; text: string } {
  const { value, type } = resolve(env, part.path, part, true);
  const t = scalarOf(type, part.path, part);
  switch (t.kind) {
    case 'raw': return { raw: true, text: serializeRaw(value, part) };
    case 'string': return { raw: false, text: requireString(value, 'string', part) };
    case 'iri': return { raw: false, text: validateIri(value, part) };
    case 'pname': return { raw: false, text: serializePname(value, part) };
    case 'int': return { raw: false, text: serializeInt(value, part) };
    case 'decimal': return { raw: false, text: serializeDecimal(value, part) };
    case 'double': return { raw: false, text: serializeDouble(value, part) };
    case 'bool': return { raw: false, text: serializeBoolean(value, part) };
    default:
      throw new TriplateTypeError(`type ${t.kind} cannot be interpolated; use it as a standalone \${ … }`, part.line, part.column);
  }
}

function resolveLang(env: Env, lang: LangSpec, pos: Pos): string {
  if ('static' in lang) return validateLangTag(lang.static, pos);
  const { value } = resolve(env, lang.path, pos, true);
  return validateLangTag(value, pos);
}

function renderInterp(env: Env, node: Extract<Node, { type: 'interp' }>): string {
  let content = '';
  for (const part of node.parts) {
    if ('text' in part) {
      content += escapeString(part.text);
      continue;
    }
    const { raw, text } = holeLexical(env, part);
    content += raw ? text : escapeString(text);
  }
  let out = `"${content}"`;
  if (node.lang) out += `@${resolveLang(env, node.lang, node)}`;
  else if (node.datatype) out += `^^${node.datatype}`;
  return out;
}

function renderIri(env: Env, node: Extract<Node, { type: 'iri' }>): string {
  let body = '';
  for (const part of node.parts) {
    if ('text' in part) {
      body += part.text;
      continue;
    }
    const { raw, text } = holeLexical(env, part);
    body += raw ? text : percentEncode(text);
  }
  return `<${validateAssembledIri(body, node)}>`;
}

function renderFor(env: Env, node: Extract<Node, { type: 'for' }>): string {
  const { value, type } = resolve(env, node.source, node, true);
  if (!type.array || !Array.isArray(value)) {
    throw new TriplateTypeError(`${node.source.join('.')} is not a list`, node.line, node.column);
  }
  const elemType: TypeExpr = { base: type.base, array: false, optional: false };
  const chunks = value.map((item) =>
    renderNodes(node.body, { schema: env.schema, context: env.context, loop: [...env.loop, { [node.item]: { value: item, type: elemType } }] }),
  );
  return joinChunks(chunks, node.join, node.joinExact, '');
}

function evalCond(env: Env, cond: Cond): boolean {
  const { value, type, present } = resolve(env, cond.path, cond, false);
  let result: boolean;
  if (type.array) {
    result = present && Array.isArray(value) && value.length > 0;
  } else if (type.base.kind === 'bool') {
    result = present && value === true;
  } else if (type.optional) {
    result = present;
  } else {
    const what = type.base.kind === 'record' ? 'object' : type.base.kind;
    throw new TriplateTypeError(
      `condition on required ${what} '${cond.path.join('.')}' is always true`,
      cond.line,
      cond.column,
    );
  }
  return cond.negated ? !result : result;
}

function renderIf(env: Env, node: IfNode): string {
  for (const branch of node.branches) {
    if (evalCond(env, branch.cond)) return renderNodes(branch.body, env);
  }
  if (node.elseBody) return renderNodes(node.elseBody, env);
  return '';
}
