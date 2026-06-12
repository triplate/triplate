import { TriplateTypeError } from '../errors.js';

export const XSD = 'http://www.w3.org/2001/XMLSchema#';

const ABSOLUTE_IRI = /^[A-Za-z][A-Za-z0-9+.-]*:[^\u0000-\u0020<>"{}|^`\\]*$/;
const PNAME =
  /^(?:[A-Za-z_][A-Za-z0-9_-]*)?:(?:[A-Za-z0-9_](?:[A-Za-z0-9_.-]*[A-Za-z0-9_-])?)?$/;
const LANG_TAG = /^[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*$/;
const DATE = /^-?\d{4,}-\d{2}-\d{2}(Z|[+-]\d{2}:\d{2})?$/;
const DATE_TIME =
  /^-?\d{4,}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})?$/;
const TIME = /^\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})?$/;
const BLANK_LABEL = /^[A-Za-z0-9_]+$/;

function fail(message: string, line?: number, column?: number): never {
  throw new TriplateTypeError(message, line, column);
}

function describe(value: unknown): string {
  if (Array.isArray(value)) return 'list';
  if (value === null) return 'null';
  return typeof value;
}

export interface Pos {
  line?: number;
  column?: number;
}

export function requireString(value: unknown, what: string, pos: Pos): string {
  if (typeof value !== 'string') {
    fail(`${what} requires a string value, got ${describe(value)}`, pos.line, pos.column);
  }
  return value;
}

export function escapeString(value: string): string {
  return value
    .replace(/\\/g, '\\\\')
    .replace(/"/g, '\\"')
    .replace(/\n/g, '\\n')
    .replace(/\r/g, '\\r')
    .replace(/\t/g, '\\t');
}

export function validateIri(value: unknown, pos: Pos): string {
  const s = requireString(value, 'iri', pos);
  if (!ABSOLUTE_IRI.test(s)) {
    fail(`invalid absolute IRI: ${JSON.stringify(s)}`, pos.line, pos.column);
  }
  return s;
}

export function serializeIri(value: unknown, pos: Pos): string {
  return `<${validateIri(value, pos)}>`;
}

export function serializePname(value: unknown, pos: Pos): string {
  const s = requireString(value, 'pname', pos);
  if (!PNAME.test(s)) {
    fail(`invalid prefixed name: ${JSON.stringify(s)}`, pos.line, pos.column);
  }
  return s;
}

export function validateLangTag(value: unknown, pos: Pos): string {
  const s = requireString(value, 'language tag', pos);
  if (!LANG_TAG.test(s)) {
    fail(`invalid language tag: ${JSON.stringify(s)}`, pos.line, pos.column);
  }
  return s;
}

export function serializeString(value: unknown, pos: Pos, lang?: string): string {
  const s = requireString(value, 'string', pos);
  const quoted = `"${escapeString(s)}"`;
  return lang ? `${quoted}@${lang}` : quoted;
}

export function serializeInt(value: unknown, pos: Pos): string {
  if (typeof value === 'bigint') return value.toString();
  if (typeof value === 'number' && Number.isSafeInteger(value)) {
    return String(value);
  }
  fail(`int requires an integral number, got ${describe(value)}`, pos.line, pos.column);
}

export function serializeDecimal(value: unknown, pos: Pos): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    fail(`decimal requires a finite number, got ${describe(value)}`, pos.line, pos.column);
  }
  const s = String(value);
  if (s.includes('e') || s.includes('E')) {
    fail(`value out of range for decimal: ${s}`, pos.line, pos.column);
  }
  return s.includes('.') ? s : `${s}.0`;
}

/**
 * Canonical xsd:double scientific notation built from the shortest
 * round-trip digits: 1.5 -> "1.5E0", 1500000 -> "1.5E6", -0.04 -> "-4.0E-2".
 * Identical algorithm in every implementation for byte parity.
 */
export function serializeDouble(value: unknown, pos: Pos): string {
  if (typeof value !== 'number') {
    fail(`double requires a number, got ${describe(value)}`, pos.line, pos.column);
  }
  if (Number.isNaN(value)) return `"NaN"^^<${XSD}double>`;
  if (value === Infinity) return `"INF"^^<${XSD}double>`;
  if (value === -Infinity) return `"-INF"^^<${XSD}double>`;
  if (value === 0) return '0.0E0';

  const sign = value < 0 ? '-' : '';
  const repr = String(Math.abs(value));
  let mantissa = repr;
  let exp = 0;
  const eIndex = repr.search(/[eE]/);
  if (eIndex >= 0) {
    mantissa = repr.slice(0, eIndex);
    exp = parseInt(repr.slice(eIndex + 1), 10);
  }
  let digits: string;
  let pointPos: number;
  const dot = mantissa.indexOf('.');
  if (dot >= 0) {
    digits = mantissa.slice(0, dot) + mantissa.slice(dot + 1);
    pointPos = dot;
  } else {
    digits = mantissa;
    pointPos = mantissa.length;
  }
  const leading = digits.length - digits.replace(/^0+/, '').length;
  digits = digits.replace(/^0+/, '').replace(/0+$/, '');
  const exponent = pointPos - leading - 1 + exp;
  const fraction = digits.slice(1) || '0';
  return `${sign}${digits[0]}.${fraction}E${exponent}`;
}

export function serializeBoolean(value: unknown, pos: Pos): string {
  if (typeof value !== 'boolean') {
    fail(`bool requires a boolean, got ${describe(value)}`, pos.line, pos.column);
  }
  return value ? 'true' : 'false';
}

function serializeTemporal(
  value: unknown,
  pos: Pos,
  name: 'date' | 'dateTime' | 'time',
  pattern: RegExp,
  fromDate: (d: Date) => string,
): string {
  let lexical: string;
  if (value instanceof Date) {
    if (Number.isNaN(value.getTime())) {
      fail(`invalid Date for ${name}`, pos.line, pos.column);
    }
    lexical = fromDate(value);
  } else if (typeof value === 'string') {
    if (!pattern.test(value)) {
      fail(`invalid ${name} value: ${JSON.stringify(value)}`, pos.line, pos.column);
    }
    lexical = value;
  } else {
    fail(`${name} requires a Date or ISO string, got ${describe(value)}`, pos.line, pos.column);
  }
  return `"${lexical}"^^<${XSD}${name}>`;
}

export function serializeDate(value: unknown, pos: Pos): string {
  return serializeTemporal(value, pos, 'date', DATE, (d) => d.toISOString().slice(0, 10));
}

export function serializeDateTime(value: unknown, pos: Pos): string {
  return serializeTemporal(value, pos, 'dateTime', DATE_TIME, (d) => d.toISOString());
}

export function serializeTime(value: unknown, pos: Pos): string {
  return serializeTemporal(value, pos, 'time', TIME, (d) => d.toISOString().slice(11, 19));
}

export function serializeTypedLiteral(value: unknown, datatype: string, pos: Pos): string {
  const s = requireString(value, `literal(${datatype})`, pos);
  return `"${escapeString(s)}"^^${datatype}`;
}

/** RDF/JS term: NamedNode, Literal or BlankNode. */
export function serializeTerm(value: unknown, pos: Pos): string {
  const term = value as { termType?: unknown; value?: unknown } | null;
  if (
    term == null ||
    typeof term !== 'object' ||
    typeof term.termType !== 'string' ||
    typeof term.value !== 'string'
  ) {
    fail(`term requires an RDF/JS term object, got ${describe(value)}`, pos.line, pos.column);
  }
  const t = term as { termType: string; value: string; language?: string; datatype?: { value?: string } };
  switch (t.termType) {
    case 'NamedNode':
      return serializeIri(t.value, pos);
    case 'BlankNode':
      if (!BLANK_LABEL.test(t.value)) {
        fail(`invalid blank node label: ${JSON.stringify(t.value)}`, pos.line, pos.column);
      }
      return `_:${t.value}`;
    case 'Literal': {
      const quoted = `"${escapeString(t.value)}"`;
      if (t.language) return `${quoted}@${validateLangTag(t.language, pos)}`;
      const dt = t.datatype?.value;
      if (dt && dt !== `${XSD}string`) return `${quoted}^^${serializeIri(dt, pos)}`;
      return quoted;
    }
    default:
      fail(`unsupported term type: ${t.termType}`, pos.line, pos.column);
  }
}

export function serializeRaw(value: unknown, pos: Pos): string {
  return requireString(value, 'raw', pos);
}

/**
 * Percent-encode a string to the IRI unreserved set `A-Za-z0-9-._~`; every
 * other character becomes its UTF-8 bytes as uppercase `%XX`. Used for holes
 * in IRI templates. Identical output to Python's urllib.parse.quote(s, safe='').
 */
export function percentEncode(s: string): string {
  return encodeURIComponent(s).replace(
    /[!'()*]/g,
    (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase(),
  );
}

/** Validate an assembled IRI-template result as an absolute IRI. */
export function validateAssembledIri(s: string, pos: Pos): string {
  if (!ABSOLUTE_IRI.test(s)) {
    fail(`IRI template did not produce a valid absolute IRI: ${JSON.stringify(s)}`, pos.line, pos.column);
  }
  return s;
}

/** Canonical lexical form for typeless interpolations inside %"…" strings. */
export function interpolationLexical(value: unknown, pos: Pos): string {
  if (typeof value === 'string') return value;
  if (typeof value === 'bigint') return value.toString();
  if (typeof value === 'number' && Number.isSafeInteger(value)) return String(value);
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  fail(
    `interpolation requires a string, int or bool (use an explicit type for other values), got ${describe(value)}`,
    pos.line,
    pos.column,
  );
}
