import { TriplateSyntaxError } from './errors.js';
import type {
  Cond,
  ExampleValue,
  LangSpec,
  ParamDecl,
  Part,
  RefPath,
  ScalarType,
  TypeBase,
  TypeExpr,
} from './ast.js';
import { hasCustomType } from './types/index.js';

export type Token =
  | { kind: 'text'; value: string }
  | { kind: 'value'; path: RefPath; spread?: boolean; join?: string; joinExact?: boolean; line: number; column: number }
  | { kind: 'interp'; parts: Part[]; lang?: LangSpec; datatype?: string; line: number; column: number }
  | { kind: 'iri'; parts: Part[]; line: number; column: number }
  | { kind: 'params'; decls: ParamDecl[]; line: number; column: number }
  | {
      kind: 'examples';
      id: string;
      description?: string;
      bindings: Record<string, ExampleValue>;
      line: number;
      column: number;
    }
  | { kind: 'for'; item: string; source: RefPath; join?: string; joinExact?: boolean; line: number; column: number }
  | { kind: 'endfor'; line: number; column: number }
  | { kind: 'if'; cond: Cond; line: number; column: number }
  | { kind: 'elif'; cond: Cond; line: number; column: number }
  | { kind: 'else'; line: number; column: number }
  | { kind: 'endif'; line: number; column: number };

const SCALARS = new Set([
  'iri', 'pname', 'string', 'int', 'decimal', 'double', 'bool',
  'date', 'datetime', 'time', 'literal', 'term', 'raw',
]);
const IRI_BODY = /^[^\u0000-\u0020<>"{}|^`\\]+$/;
const PNAME_TOKEN =
  /^(?:[A-Za-z_][A-Za-z0-9_-]*)?:(?:[A-Za-z0-9_](?:[A-Za-z0-9_.-]*[A-Za-z0-9_-])?)?$/;
const ESCAPES: Record<string, string> = { '\\': '\\', '"': '"', n: '\n', r: '\r', t: '\t' };

const isIdentStart = (c: string) => /[A-Za-z_]/.test(c);
const isIdentChar = (c: string) => /[A-Za-z0-9_]/.test(c);
const isSlugChar = (c: string) => /[A-Za-z0-9_-]/.test(c);
const isLetter = (c: string) => /[A-Za-z]/.test(c);
const isLangChar = (c: string) => /[A-Za-z0-9-]/.test(c);
const isDigit = (c: string) => /[0-9]/.test(c);

export function tokenize(source: string): Token[] {
  return new Lexer(source).run();
}

class Lexer {
  private readonly s: string;
  private pos = 0;
  private line = 1;
  private col = 1;
  private tokens: Token[] = [];
  private buf = '';
  private lineHasContent = false;
  private stringDelim: string | null = null;

  constructor(source: string) {
    this.s = source;
  }

  run(): Token[] {
    if (this.atDashLine()) this.lexFrontmatter();
    while (this.pos < this.s.length) {
      const ch = this.s[this.pos];
      if (this.stringDelim !== null) {
        if (this.s.startsWith(this.stringDelim, this.pos)) {
          this.takeText(this.stringDelim.length);
          this.stringDelim = null;
        } else if (this.stringDelim.length === 1 && ch === '\\' && this.pos + 1 < this.s.length) {
          this.takeText(2);
        } else if (this.stringDelim.length === 1 && ch === '\n') {
          this.stringDelim = null;
          this.takeText(1);
        } else {
          this.takeText(1);
        }
        continue;
      }
      if (ch === '$' && this.peek(1) === '{') this.lexValue();
      else if (ch === '$' && this.peek(1) === '"') this.lexInterpString();
      else if (ch === '$' && this.peek(1) === '<') this.lexIriTemplate();
      else if (ch === '{' && this.peek(1) === '%') this.lexTag();
      else if (ch === '#') this.lexCommentAsText();
      else if (ch === '"' || ch === "'") this.enterString(ch);
      else if (ch === '<') this.tryIriRef();
      else this.takeText(1);
    }
    this.flushText();
    return this.tokens;
  }

  // ---- primitives --------------------------------------------------------

  private peek(o = 0): string {
    return this.s[this.pos + o] ?? '';
  }
  private advance(n = 1): string {
    const out = this.s.slice(this.pos, this.pos + n);
    for (const c of out) {
      if (c === '\n') {
        this.line++;
        this.col = 1;
        this.lineHasContent = false;
      } else {
        this.col++;
        if (c !== ' ' && c !== '\t' && c !== '\r') this.lineHasContent = true;
      }
    }
    this.pos += n;
    return out;
  }
  private takeText(n: number): void {
    this.buf += this.advance(n);
  }
  private flushText(): void {
    if (this.buf.length > 0) {
      this.tokens.push({ kind: 'text', value: this.buf });
      this.buf = '';
    }
  }
  private error(message: string, line = this.line, column = this.col): never {
    throw new TriplateSyntaxError(message, line, column);
  }
  private skipInline(): void {
    while (this.peek() === ' ' || this.peek() === '\t') this.advance(1);
  }
  private skipWs(): void {
    while (this.peek() !== '' && /[\s,]/.test(this.peek())) this.advance(1);
  }
  private enterString(quote: string): void {
    const triple = quote.repeat(3);
    if (this.s.startsWith(triple, this.pos)) {
      this.stringDelim = triple;
      this.takeText(3);
    } else {
      this.stringDelim = quote;
      this.takeText(1);
    }
  }
  private lexCommentAsText(): void {
    const end = this.s.indexOf('\n', this.pos);
    this.takeText((end < 0 ? this.s.length : end) - this.pos);
  }
  private tryIriRef(): void {
    let i = this.pos + 1;
    while (i < this.s.length && !' \t\n\r<>"'.includes(this.s[i])) i++;
    if (i < this.s.length && this.s[i] === '>') this.takeText(i + 1 - this.pos);
    else this.takeText(1);
  }

  private readIdent(): string {
    if (!isIdentStart(this.peek())) this.error('expected an identifier');
    let out = '';
    while (isIdentChar(this.peek())) out += this.advance(1);
    return out;
  }
  private readPath(): RefPath {
    const parts = [this.readIdent()];
    while (this.peek() === '.' && isIdentStart(this.peek(1))) {
      this.advance(1);
      parts.push(this.readIdent());
    }
    return parts;
  }
  private readInt(): number {
    if (!isDigit(this.peek())) this.error('expected an int');
    let d = '';
    while (isDigit(this.peek())) d += this.advance(1);
    return parseInt(d, 10);
  }
  private readQuotedString(): string {
    if (this.peek() !== '"') this.error('expected a quoted string');
    this.advance(1);
    let out = '';
    for (;;) {
      const ch = this.peek();
      if (ch === '' || ch === '\n') this.error('unterminated quoted string');
      if (ch === '"') {
        this.advance(1);
        return out;
      }
      if (ch === '\\') {
        const e = this.peek(1);
        if (!(e in ESCAPES)) this.error(`invalid escape \\${e}`);
        this.advance(2);
        out += ESCAPES[e];
        continue;
      }
      out += this.advance(1);
    }
  }
  private readDatatypeRef(): string {
    if (this.peek() === '<') {
      this.advance(1);
      let body = '';
      while (this.peek() !== '>' && this.peek() !== '' && this.peek() !== '\n') body += this.advance(1);
      if (this.peek() !== '>') this.error('unterminated IRI reference');
      this.advance(1);
      if (!IRI_BODY.test(body)) this.error(`invalid IRI reference: <${body}>`);
      return `<${body}>`;
    }
    let tok = '';
    while (this.peek() !== '' && /[A-Za-z0-9_.:-]/.test(this.peek())) tok += this.advance(1);
    if (!PNAME_TOKEN.test(tok)) this.error(`invalid prefixed name: ${tok}`);
    return tok;
  }

  // ---- value constructs --------------------------------------------------

  private lexValue(): void {
    const line = this.line;
    const col = this.col;
    this.flushText();
    this.advance(2); // ${
    this.skipInline();
    let spread = false;
    if (this.peek() === '.' && this.peek(1) === '.' && this.peek(2) === '.') {
      this.advance(3);
      spread = true;
      this.skipInline();
    }
    const path = this.readPath();
    let join: string | undefined;
    let joinExact = false;
    if (spread) {
      ({ join, joinExact } = this.readJoinClause(() => this.peek() === '}', '${ … }'));
    }
    this.skipInline();
    if (this.peek() !== '}') this.error('unterminated ${ … }', line, col);
    this.advance(1);
    this.tokens.push({ kind: 'value', path, spread, join, joinExact, line, column: col });
  }

  private readHole(): { path: RefPath; line: number; column: number } {
    const line = this.line;
    const col = this.col;
    this.advance(2); // ${
    this.skipInline();
    const path = this.readPath();
    this.skipInline();
    if (this.peek() !== '}') this.error('unterminated ${ … } hole', line, col);
    this.advance(1);
    return { path, line, column: col };
  }

  private lexInterpString(): void {
    const line = this.line;
    const col = this.col;
    this.flushText();
    this.advance(2); // $"
    const parts: Part[] = [];
    let text = '';
    const flush = () => {
      if (text.length > 0) {
        parts.push({ text });
        text = '';
      }
    };
    for (;;) {
      const ch = this.peek();
      if (ch === '' || ch === '\n') this.error('unterminated $"…" string literal', line, col);
      if (ch === '"') {
        this.advance(1);
        break;
      }
      if (ch === '\\') {
        const e = this.peek(1);
        if (!(e in ESCAPES)) this.error(`invalid escape \\${e} in $"…"`);
        this.advance(2);
        text += ESCAPES[e];
        continue;
      }
      if (ch === '$' && this.peek(1) === '{') {
        flush();
        parts.push(this.readHole());
        continue;
      }
      text += this.advance(1);
    }
    flush();
    let lang: LangSpec | undefined;
    let datatype: string | undefined;
    if (this.peek() === '@') {
      this.advance(1);
      if (this.peek() === '$' && this.peek(1) === '{') {
        lang = { path: this.readHole().path };
      } else {
        if (!isLetter(this.peek())) this.error('expected a language tag after @');
        let tag = '';
        while (isLangChar(this.peek())) tag += this.advance(1);
        lang = { static: tag };
      }
    } else if (this.peek() === '^' && this.peek(1) === '^') {
      this.advance(2);
      datatype = this.readDatatypeRef();
    }
    this.tokens.push({ kind: 'interp', parts, lang, datatype, line, column: col });
  }

  private lexIriTemplate(): void {
    const line = this.line;
    const col = this.col;
    this.flushText();
    this.advance(2); // $<
    const parts: Part[] = [];
    let text = '';
    const flush = () => {
      if (text.length > 0) {
        parts.push({ text });
        text = '';
      }
    };
    for (;;) {
      const ch = this.peek();
      if (ch === '' || ch === '\n') this.error('unterminated $<…> IRI template', line, col);
      if (ch === '>') {
        this.advance(1);
        break;
      }
      if (ch === '$' && this.peek(1) === '{') {
        flush();
        parts.push(this.readHole());
        continue;
      }
      text += this.advance(1);
    }
    flush();
    this.tokens.push({ kind: 'iri', parts, line, column: col });
  }

  // ---- {% … %} tags ------------------------------------------------------

  private lexTag(): void {
    const line = this.line;
    const col = this.col;
    const lineClean = !this.lineHasContent;
    if (lineClean) this.buf = this.buf.replace(/[ \t]+$/, '');
    this.flushText();
    this.advance(2); // {%
    this.skipInline();
    const keyword = isIdentStart(this.peek())
      ? this.readIdent().toLowerCase()
      : this.error('expected a directive keyword');
    switch (keyword) {
      case 'for': {
        const f = this.readForHeader();
        this.tokens.push({ kind: 'for', item: f.item, source: f.source, join: f.join, joinExact: f.joinExact, line, column: col });
        break;
      }
      case 'endfor':
        this.endTag('endfor');
        this.tokens.push({ kind: 'endfor', line, column: col });
        break;
      case 'if':
        this.tokens.push({ kind: 'if', cond: this.readCond(), line, column: col });
        this.endTag('if');
        break;
      case 'elif':
        this.tokens.push({ kind: 'elif', cond: this.readCond(), line, column: col });
        this.endTag('elif');
        break;
      case 'else':
        this.endTag('else');
        this.tokens.push({ kind: 'else', line, column: col });
        break;
      case 'endif':
        this.endTag('endif');
        this.tokens.push({ kind: 'endif', line, column: col });
        break;
      default:
        this.error(`unknown directive: ${keyword}`, line, col);
    }
    if (lineClean) this.trimTrailingNewline();
  }

  private atTagEnd(): boolean {
    return this.peek() === '%' && this.peek(1) === '}';
  }
  private endTag(what: string): void {
    this.skipInline();
    if (!this.atTagEnd()) this.error(`unexpected content in %${what} directive`);
    this.advance(2);
  }
  private trimTrailingNewline(): void {
    if (this.peek() === '\r') this.advance(1);
    if (this.peek() === '\n') this.advance(1);
  }

  /** Parse an optional `join "<sep>" [explicit]` clause, stopping at `atEnd`. */
  private readJoinClause(atEnd: () => boolean, context: string): { join?: string; joinExact: boolean } {
    let join: string | undefined;
    let joinExact = false;
    let seenJoin = false;
    for (;;) {
      this.skipInline();
      if (atEnd() || !isIdentStart(this.peek())) break;
      const word = this.readIdent().toLowerCase();
      if (word === 'join') {
        if (seenJoin) this.error('duplicate join');
        this.skipInline();
        join = this.readQuotedString();
        seenJoin = true;
      } else if (word === 'explicit') {
        if (!seenJoin) this.error("'explicit' requires a preceding join");
        joinExact = true;
      } else {
        this.error(`unexpected token in ${context}: ${word}`);
      }
    }
    return { join, joinExact };
  }

  private readForHeader(): { item: string; source: RefPath; join?: string; joinExact?: boolean } {
    this.skipInline();
    const item = this.readIdent();
    this.skipInline();
    if (this.readIdent().toLowerCase() !== 'in') this.error("expected 'in' in %for");
    this.skipInline();
    const source = this.readPath();
    const { join, joinExact } = this.readJoinClause(() => this.atTagEnd(), '%for');
    this.skipInline();
    if (!this.atTagEnd()) this.error('unexpected content in %for directive');
    this.advance(2);
    return { item, source, join, joinExact };
  }

  private readCond(): Cond {
    this.skipInline();
    const line = this.line;
    const col = this.col;
    let negated = false;
    if (isIdentStart(this.peek())) {
      const before = { pos: this.pos, line: this.line, col: this.col };
      const w = this.readIdent();
      if (w.toLowerCase() === 'not') {
        negated = true;
        this.skipInline();
      } else {
        this.pos = before.pos;
        this.line = before.line;
        this.col = before.col;
      }
    }
    const path = this.readPath();
    return { negated, path, line, column: col };
  }

  // ---- --- frontmatter header --------------------------------------------

  /** True if the cursor is at a line that is just `---` (the frontmatter fence). */
  private atDashLine(): boolean {
    if (!this.s.startsWith('---', this.pos)) return false;
    let i = this.pos + 3;
    while (i < this.s.length && (this.s[i] === ' ' || this.s[i] === '\t' || this.s[i] === '\r')) i++;
    return i >= this.s.length || this.s[i] === '\n';
  }

  private consumeDashLine(): void {
    this.advance(3); // ---
    while (this.peek() === ' ' || this.peek() === '\t') this.advance(1);
    if (this.peek() === '\r') this.advance(1);
    if (this.peek() === '\n') this.advance(1);
  }

  /** Whitespace (incl. newlines), commas and `#` comments between frontmatter items. */
  private skipFront(): void {
    for (;;) {
      const c = this.peek();
      if (c === ' ' || c === '\t' || c === '\n' || c === '\r' || c === ',') {
        this.advance(1);
        continue;
      }
      if (c === '#') {
        while (this.peek() !== '' && this.peek() !== '\n') this.advance(1);
        continue;
      }
      break;
    }
  }

  private lexFrontmatter(): void {
    const fmLine = this.line;
    const fmCol = this.col;
    this.consumeDashLine();
    for (;;) {
      this.skipFront();
      if (this.peek() === '') this.error('unterminated frontmatter (--- … ---)', fmLine, fmCol);
      if (this.atDashLine()) {
        this.consumeDashLine();
        return;
      }
      const line = this.line;
      const col = this.col;
      const kw = this.readIdent().toLowerCase();
      if (kw === 'params') this.readFrontParams(line, col);
      else if (kw === 'example') this.readFrontExample(line, col);
      else this.error(`unknown frontmatter section: ${kw}`, line, col);
    }
  }

  private expectBrace(what: string, line: number, col: number): void {
    this.skipFront();
    if (this.peek() !== '{') this.error(`expected '{' after ${what}`, line, col);
    this.advance(1);
  }

  private readFrontParams(line: number, col: number): void {
    this.expectBrace('params', line, col);
    const decls: ParamDecl[] = [];
    for (;;) {
      this.skipFront();
      if (this.peek() === '}') {
        this.advance(1);
        break;
      }
      if (this.peek() === '') this.error('unterminated params { … }', line, col);
      const name = this.readIdent();
      this.skipInline();
      if (this.peek() !== ':') this.error(`expected ':' after parameter '${name}'`);
      this.advance(1);
      this.skipInline();
      decls.push({ name, type: this.readTypeExpr() });
    }
    this.tokens.push({ kind: 'params', decls, line, column: col });
  }

  private readFrontExample(line: number, col: number): void {
    this.skipInline();
    if (!isIdentStart(this.peek())) this.error('expected an example id', line, col);
    let id = '';
    while (isSlugChar(this.peek())) id += this.advance(1);
    this.skipInline();
    let description: string | undefined;
    if (this.peek() === '"') description = this.readQuotedString();
    this.expectBrace('example', line, col);
    const bindings: Record<string, ExampleValue> = {};
    for (;;) {
      this.skipFront();
      if (this.peek() === '}') {
        this.advance(1);
        break;
      }
      if (this.peek() === '') this.error('unterminated example { … }', line, col);
      const name = this.readIdent();
      this.skipInline();
      if (this.peek() !== ':') this.error(`expected ':' after '${name}' in example`);
      this.advance(1);
      bindings[name] = this.readExampleValue();
    }
    this.tokens.push({ kind: 'examples', id, description, bindings, line, column: col });
  }

  private readTypeExpr(): TypeExpr {
    const base = this.readTypeBase();
    let array = false;
    let optional = false;
    let min: number | undefined;
    let max: number | undefined;
    if (this.peek() === '[' && this.peek(1) === ']') {
      this.advance(2);
      array = true;
    }
    for (;;) {
      this.skipInline();
      if (!isIdentStart(this.peek())) break;
      const before = { pos: this.pos, line: this.line, col: this.col };
      const word = this.readIdent().toLowerCase();
      if (word === 'optional') {
        optional = true;
      } else if (word === 'min' || word === 'max') {
        if (!array) this.error('min/max apply only to arrays ([])');
        this.skipInline();
        const n = this.readInt();
        if (word === 'min') min = n;
        else max = n;
      } else {
        this.pos = before.pos;
        this.line = before.line;
        this.col = before.col;
        break;
      }
    }
    return { base, array, optional, min, max };
  }

  private readTypeBase(): TypeBase {
    if (this.peek() === '{') return this.readRecordType();
    const line = this.line;
    const col = this.col;
    const ident = this.readIdent();
    const low = ident.toLowerCase();
    if (low === 'literal') {
      if (this.peek() !== '(') this.error("expected '(' after literal");
      this.advance(1);
      const datatype = this.readDatatypeRef();
      if (this.peek() !== ')') this.error("expected ')' after literal datatype");
      this.advance(1);
      return { kind: 'literal', datatype };
    }
    if (SCALARS.has(low)) {
      return { kind: low === 'datetime' ? 'dateTime' : low } as ScalarType;
    }
    if (hasCustomType(low)) return { kind: 'custom', name: low };
    this.error(`unknown type: ${ident}`, line, col);
  }

  private readRecordType(): TypeBase {
    this.advance(1); // {
    const fields: Record<string, TypeExpr> = {};
    for (;;) {
      this.skipWs();
      if (this.peek() === '}') {
        this.advance(1);
        break;
      }
      if (this.peek() === '') this.error('unterminated record type');
      const name = this.readIdent();
      this.skipInline();
      if (this.peek() !== ':') this.error(`expected ':' after field '${name}'`);
      this.advance(1);
      this.skipInline();
      fields[name] = this.readTypeExpr();
    }
    return { kind: 'record', fields };
  }

  // ---- example values (RDF term literals) --------------------------------

  private readExampleValue(): ExampleValue {
    this.skipInline();
    const ch = this.peek();
    if (ch === '<') return { kind: 'iri', value: this.readDatatypeRef().slice(1, -1) };
    if (ch === '"') {
      const value = this.readQuotedString();
      if (this.peek() === '@') {
        this.advance(1);
        let lang = '';
        while (isLangChar(this.peek())) lang += this.advance(1);
        return { kind: 'string', value, lang };
      }
      if (this.peek() === '^' && this.peek(1) === '^') {
        this.advance(2);
        return { kind: 'string', value, datatype: this.readDatatypeRef() };
      }
      return { kind: 'string', value };
    }
    if (ch === '[') return this.readExampleList();
    if (ch === '{') return this.readExampleRecord();
    if (ch === '-' || isDigit(ch)) {
      let num = ch === '-' ? this.advance(1) : '';
      while (isDigit(this.peek()) || '.eE+-'.includes(this.peek())) num += this.advance(1);
      return { kind: 'number', value: Number(num) };
    }
    if (isLetter(ch)) {
      const word = this.readIdent();
      if (word === 'true' || word === 'false') return { kind: 'bool', value: word === 'true' };
      if (this.peek() === ':') {
        this.advance(1);
        let local = '';
        while (this.peek() !== '' && /[A-Za-z0-9_.-]/.test(this.peek())) local += this.advance(1);
        return { kind: 'pname', prefix: word, local };
      }
      this.error(`invalid example value starting with '${word}'`);
    }
    this.error('expected an example value');
  }

  private readExampleList(): ExampleValue {
    this.advance(1); // [
    const items: ExampleValue[] = [];
    for (;;) {
      this.skipWs();
      if (this.peek() === ']') {
        this.advance(1);
        break;
      }
      if (this.peek() === '') this.error('unterminated example list');
      items.push(this.readExampleValue());
    }
    return { kind: 'list', items };
  }

  private readExampleRecord(): ExampleValue {
    this.advance(1); // {
    const fields: Record<string, ExampleValue> = {};
    for (;;) {
      this.skipWs();
      if (this.peek() === '}') {
        this.advance(1);
        break;
      }
      if (this.peek() === '') this.error('unterminated example record');
      const name = this.readIdent();
      this.skipInline();
      if (this.peek() !== ':') this.error(`expected ':' after field '${name}'`);
      this.advance(1);
      fields[name] = this.readExampleValue();
    }
    return { kind: 'record', fields };
  }
}
