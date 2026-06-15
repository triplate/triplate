package dev.triplate;

import dev.triplate.Ast.Cond;
import dev.triplate.Ast.ExampleValue;
import dev.triplate.Ast.LangSpec;
import dev.triplate.Ast.Part;
import dev.triplate.Ast.ParamDecl;
import dev.triplate.Ast.ScalarKind;
import dev.triplate.Ast.ScalarType;
import dev.triplate.Ast.TypeBase;
import dev.triplate.Ast.TypeExpr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Single-pass tokenizer. Faithful port of {@code lexer.ts}. */
final class Lexer {

  // ---- tokens --------------------------------------------------------------

  sealed interface Token
      permits Txt, Val, Interp, Iri, ParamsTok, ExampleTok, ForTok, EndForTok, IfTok, ElifTok, ElseTok, EndIfTok {}

  record Txt(String value) implements Token {}

  record Val(List<String> path, boolean spread, String join, boolean joinExact, int line, int column)
      implements Token {}

  record Interp(List<Part> parts, LangSpec lang, String datatype, int line, int column) implements Token {}

  record Iri(List<Part> parts, int line, int column) implements Token {}

  record ParamsTok(List<ParamDecl> decls, int line, int column) implements Token {}

  record ExampleTok(String id, String description, Map<String, ExampleValue> bindings, int line, int column)
      implements Token {}

  record ForTok(String item, List<String> source, String join, boolean joinExact, int line, int column)
      implements Token {}

  record EndForTok(int line, int column) implements Token {}

  record IfTok(Cond cond, int line, int column) implements Token {}

  record ElifTok(Cond cond, int line, int column) implements Token {}

  record ElseTok(int line, int column) implements Token {}

  record EndIfTok(int line, int column) implements Token {}

  // ---- scalar type vocabulary ----------------------------------------------

  private static final Set<String> SCALARS = Set.of(
      "iri", "pname", "string", "int", "decimal", "double", "bool",
      "date", "datetime", "time", "literal", "term", "raw");

  private static ScalarKind scalarKind(String low) {
    return switch (low) {
      case "iri" -> ScalarKind.IRI;
      case "pname" -> ScalarKind.PNAME;
      case "string" -> ScalarKind.STRING;
      case "int" -> ScalarKind.INT;
      case "decimal" -> ScalarKind.DECIMAL;
      case "double" -> ScalarKind.DOUBLE;
      case "bool" -> ScalarKind.BOOL;
      case "date" -> ScalarKind.DATE;
      case "datetime" -> ScalarKind.DATE_TIME;
      case "time" -> ScalarKind.TIME;
      case "term" -> ScalarKind.TERM;
      case "raw" -> ScalarKind.RAW;
      default -> throw new IllegalStateException(low);
    };
  }

  // ---- state ---------------------------------------------------------------

  private final String s;
  private int pos = 0;
  private int line = 1;
  private int col = 1;
  private final List<Token> tokens = new ArrayList<>();
  private StringBuilder buf = new StringBuilder();
  private boolean lineHasContent = false;
  private String stringDelim = null;

  Lexer(String source) {
    this.s = source;
  }

  static List<Token> tokenize(String source) {
    return new Lexer(source).run();
  }

  List<Token> run() {
    if (atDashLine()) lexFrontmatter();
    while (pos < s.length()) {
      char ch = s.charAt(pos);
      if (stringDelim != null) {
        if (s.startsWith(stringDelim, pos)) {
          takeText(stringDelim.length());
          stringDelim = null;
        } else if (stringDelim.length() == 1 && ch == '\\' && pos + 1 < s.length()) {
          takeText(2);
        } else if (stringDelim.length() == 1 && ch == '\n') {
          stringDelim = null;
          takeText(1);
        } else {
          takeText(1);
        }
        continue;
      }
      if (ch == '$' && peek(1) == '{') lexValue();
      else if (ch == '$' && peek(1) == '"') lexInterpString();
      else if (ch == '$' && peek(1) == '<') lexIriTemplate();
      else if (ch == '{' && peek(1) == '%') lexTag();
      else if (ch == '#') lexCommentAsText();
      else if (ch == '"' || ch == '\'') enterString(ch);
      else if (ch == '<') tryIriRef();
      else takeText(1);
    }
    flushText();
    return tokens;
  }

  // ---- primitives ----------------------------------------------------------

  private char peek() {
    return peek(0);
  }

  private char peek(int o) {
    int i = pos + o;
    return i < s.length() ? s.charAt(i) : '\0';
  }

  private String advance(int n) {
    int end = Math.min(pos + n, s.length());
    String out = s.substring(pos, end);
    for (int i = 0; i < out.length(); i++) {
      char c = out.charAt(i);
      if (c == '\n') {
        line++;
        col = 1;
        lineHasContent = false;
      } else {
        col++;
        if (c != ' ' && c != '\t' && c != '\r') lineHasContent = true;
      }
    }
    pos = end;
    return out;
  }

  private String advance() {
    return advance(1);
  }

  private void takeText(int n) {
    buf.append(advance(n));
  }

  private void flushText() {
    if (buf.length() > 0) {
      tokens.add(new Txt(buf.toString()));
      buf = new StringBuilder();
    }
  }

  private TriplateSyntaxError error(String message) {
    return error(message, line, col);
  }

  private TriplateSyntaxError error(String message, int l, int c) {
    return new TriplateSyntaxError(message, l, c);
  }

  private void skipInline() {
    while (peek() == ' ' || peek() == '\t') advance(1);
  }

  private void skipWs() {
    while (pos < s.length() && (isJsSpace(peek()) || peek() == ',')) advance(1);
  }

  private static boolean isJsSpace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B;
  }

  private void enterString(char quote) {
    String triple = "" + quote + quote + quote;
    if (s.startsWith(triple, pos)) {
      stringDelim = triple;
      takeText(3);
    } else {
      stringDelim = String.valueOf(quote);
      takeText(1);
    }
  }

  private void lexCommentAsText() {
    int end = s.indexOf('\n', pos);
    takeText((end < 0 ? s.length() : end) - pos);
  }

  private void tryIriRef() {
    int i = pos + 1;
    while (i < s.length() && " \t\n\r<>\"".indexOf(s.charAt(i)) < 0) i++;
    if (i < s.length() && s.charAt(i) == '>') takeText(i + 1 - pos);
    else takeText(1);
  }

  private static boolean isIdentStart(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
  }

  private static boolean isIdentChar(char c) {
    return isIdentStart(c) || (c >= '0' && c <= '9');
  }

  private static boolean isSlugChar(char c) {
    return isIdentChar(c) || c == '-';
  }

  private static boolean isLetter(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
  }

  private static boolean isLangChar(char c) {
    return isLetter(c) || (c >= '0' && c <= '9') || c == '-';
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private String readIdent() {
    if (!isIdentStart(peek())) throw error("expected an identifier");
    StringBuilder out = new StringBuilder();
    while (isIdentChar(peek())) out.append(advance(1));
    return out.toString();
  }

  private List<String> readPath() {
    List<String> parts = new ArrayList<>();
    parts.add(readIdent());
    while (peek() == '.' && isIdentStart(peek(1))) {
      advance(1);
      parts.add(readIdent());
    }
    return parts;
  }

  private int readInt() {
    if (!isDigit(peek())) throw error("expected an int");
    StringBuilder d = new StringBuilder();
    while (isDigit(peek())) d.append(advance(1));
    return Integer.parseInt(d.toString());
  }

  private static boolean isEscape(char e) {
    return e == '\\' || e == '"' || e == 'n' || e == 'r' || e == 't';
  }

  private static char unescape(char e) {
    return switch (e) {
      case '\\' -> '\\';
      case '"' -> '"';
      case 'n' -> '\n';
      case 'r' -> '\r';
      case 't' -> '\t';
      default -> throw new IllegalStateException();
    };
  }

  private String readQuotedString() {
    if (peek() != '"') throw error("expected a quoted string");
    advance(1);
    StringBuilder out = new StringBuilder();
    for (; ; ) {
      char ch = peek();
      if (ch == '\0' || ch == '\n') throw error("unterminated quoted string");
      if (ch == '"') {
        advance(1);
        return out.toString();
      }
      if (ch == '\\') {
        char e = peek(1);
        if (!isEscape(e)) throw error("invalid escape \\" + e);
        advance(2);
        out.append(unescape(e));
        continue;
      }
      out.append(advance(1));
    }
  }

  private static boolean isDatatypePnameChar(char c) {
    return isLetter(c) || isDigit(c) || c == '_' || c == '.' || c == ':' || c == '-';
  }

  private static final java.util.regex.Pattern IRI_BODY =
      java.util.regex.Pattern.compile("^[^\\u0000-\\u0020<>\"{}|^`\\\\]+$");
  private static final java.util.regex.Pattern PNAME_TOKEN =
      java.util.regex.Pattern.compile("^(?:[A-Za-z_][A-Za-z0-9_-]*)?:(?:[A-Za-z0-9_](?:[A-Za-z0-9_.-]*[A-Za-z0-9_-])?)?$");

  private String readDatatypeRef() {
    if (peek() == '<') {
      advance(1);
      StringBuilder body = new StringBuilder();
      while (peek() != '>' && peek() != '\0' && peek() != '\n') body.append(advance(1));
      if (peek() != '>') throw error("unterminated IRI reference");
      advance(1);
      if (!IRI_BODY.matcher(body.toString()).matches()) throw error("invalid IRI reference: <" + body + ">");
      return "<" + body + ">";
    }
    StringBuilder tok = new StringBuilder();
    while (peek() != '\0' && isDatatypePnameChar(peek())) tok.append(advance(1));
    if (!PNAME_TOKEN.matcher(tok.toString()).matches()) throw error("invalid prefixed name: " + tok);
    return tok.toString();
  }

  // ---- value constructs ----------------------------------------------------

  private void lexValue() {
    int startLine = line;
    int startCol = col;
    flushText();
    advance(2); // ${
    skipInline();
    boolean spread = false;
    if (peek() == '.' && peek(1) == '.' && peek(2) == '.') {
      advance(3);
      spread = true;
      skipInline();
    }
    List<String> path = readPath();
    String join = null;
    boolean joinExact = false;
    if (spread) {
      JoinClause jc = readJoinClause(() -> peek() == '}', "${ … }");
      join = jc.join();
      joinExact = jc.joinExact();
    }
    skipInline();
    if (peek() != '}') throw error("unterminated ${ … }", startLine, startCol);
    advance(1);
    tokens.add(new Val(path, spread, join, joinExact, startLine, startCol));
  }

  private record Hole(List<String> path, int line, int column) {}

  private Hole readHole() {
    int startLine = line;
    int startCol = col;
    advance(2); // ${
    skipInline();
    List<String> path = readPath();
    skipInline();
    if (peek() != '}') throw error("unterminated ${ … } hole", startLine, startCol);
    advance(1);
    return new Hole(path, startLine, startCol);
  }

  private void lexInterpString() {
    int startLine = line;
    int startCol = col;
    flushText();
    advance(2); // $"
    List<Part> parts = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    for (; ; ) {
      char ch = peek();
      if (ch == '\0' || ch == '\n') throw error("unterminated $\"…\" string literal", startLine, startCol);
      if (ch == '"') {
        advance(1);
        break;
      }
      if (ch == '\\') {
        char e = peek(1);
        if (!isEscape(e)) throw error("invalid escape \\" + e + " in $\"…\"");
        advance(2);
        text.append(unescape(e));
        continue;
      }
      if (ch == '$' && peek(1) == '{') {
        flushPart(parts, text);
        Hole h = readHole();
        parts.add(new Ast.HolePart(h.path(), h.line(), h.column()));
        continue;
      }
      text.append(advance(1));
    }
    flushPart(parts, text);
    LangSpec lang = null;
    String datatype = null;
    if (peek() == '@') {
      advance(1);
      if (peek() == '$' && peek(1) == '{') {
        lang = new Ast.PathLang(readHole().path());
      } else {
        if (!isLetter(peek())) throw error("expected a language tag after @");
        StringBuilder tag = new StringBuilder();
        while (isLangChar(peek())) tag.append(advance(1));
        lang = new Ast.StaticLang(tag.toString());
      }
    } else if (peek() == '^' && peek(1) == '^') {
      advance(2);
      datatype = readDatatypeRef();
    }
    tokens.add(new Interp(parts, lang, datatype, startLine, startCol));
  }

  private void lexIriTemplate() {
    int startLine = line;
    int startCol = col;
    flushText();
    advance(2); // $<
    List<Part> parts = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    for (; ; ) {
      char ch = peek();
      if (ch == '\0' || ch == '\n') throw error("unterminated $<…> IRI template", startLine, startCol);
      if (ch == '>') {
        advance(1);
        break;
      }
      if (ch == '$' && peek(1) == '{') {
        flushPart(parts, text);
        Hole h = readHole();
        parts.add(new Ast.HolePart(h.path(), h.line(), h.column()));
        continue;
      }
      text.append(advance(1));
    }
    flushPart(parts, text);
    tokens.add(new Iri(parts, startLine, startCol));
  }

  private static void flushPart(List<Part> parts, StringBuilder text) {
    if (text.length() > 0) {
      parts.add(new Ast.TextPart(text.toString()));
      text.setLength(0);
    }
  }

  // ---- {% … %} tags --------------------------------------------------------

  private void lexTag() {
    int startLine = line;
    int startCol = col;
    boolean lineClean = !lineHasContent;
    if (lineClean) trimTrailingInlineWs();
    flushText();
    advance(2); // {%
    skipInline();
    if (!isIdentStart(peek())) throw error("expected a directive keyword", startLine, startCol);
    String keyword = readIdent().toLowerCase();
    switch (keyword) {
      case "for" -> {
        ForTok f = readForHeader(startLine, startCol);
        tokens.add(f);
      }
      case "endfor" -> {
        endTag("endfor");
        tokens.add(new EndForTok(startLine, startCol));
      }
      case "if" -> {
        tokens.add(new IfTok(readCond(), startLine, startCol));
        endTag("if");
      }
      case "elif" -> {
        tokens.add(new ElifTok(readCond(), startLine, startCol));
        endTag("elif");
      }
      case "else" -> {
        endTag("else");
        tokens.add(new ElseTok(startLine, startCol));
      }
      case "endif" -> {
        endTag("endif");
        tokens.add(new EndIfTok(startLine, startCol));
      }
      default -> throw error("unknown directive: " + keyword, startLine, startCol);
    }
    if (lineClean) trimTrailingNewline();
  }

  /** Strip trailing spaces/tabs from the pending text buffer (a clean directive line). */
  private void trimTrailingInlineWs() {
    int end = buf.length();
    while (end > 0 && (buf.charAt(end - 1) == ' ' || buf.charAt(end - 1) == '\t')) end--;
    buf.setLength(end);
  }

  private boolean atTagEnd() {
    return peek() == '%' && peek(1) == '}';
  }

  private void endTag(String what) {
    skipInline();
    if (!atTagEnd()) throw error("unexpected content in %" + what + " directive");
    advance(2);
  }

  private void trimTrailingNewline() {
    if (peek() == '\r') advance(1);
    if (peek() == '\n') advance(1);
  }

  private record JoinClause(String join, boolean joinExact) {}

  /** Parse an optional {@code join "<sep>" [explicit]} clause, stopping at {@code atEnd}. */
  private JoinClause readJoinClause(java.util.function.BooleanSupplier atEnd, String context) {
    String join = null;
    boolean joinExact = false;
    boolean seenJoin = false;
    for (; ; ) {
      skipInline();
      if (atEnd.getAsBoolean() || !isIdentStart(peek())) break;
      String word = readIdent().toLowerCase();
      if (word.equals("join")) {
        if (seenJoin) throw error("duplicate join");
        skipInline();
        join = readQuotedString();
        seenJoin = true;
      } else if (word.equals("explicit")) {
        if (!seenJoin) throw error("'explicit' requires a preceding join");
        joinExact = true;
      } else {
        throw error("unexpected token in " + context + ": " + word);
      }
    }
    return new JoinClause(join, joinExact);
  }

  private ForTok readForHeader(int startLine, int startCol) {
    skipInline();
    String item = readIdent();
    skipInline();
    if (!readIdent().equalsIgnoreCase("in")) throw error("expected 'in' in %for");
    skipInline();
    List<String> source = readPath();
    JoinClause jc = readJoinClause(this::atTagEnd, "%for");
    skipInline();
    if (!atTagEnd()) throw error("unexpected content in %for directive");
    advance(2);
    return new ForTok(item, source, jc.join(), jc.joinExact(), startLine, startCol);
  }

  private Cond readCond() {
    skipInline();
    int condLine = line;
    int condCol = col;
    boolean negated = false;
    if (isIdentStart(peek())) {
      int savePos = pos;
      int saveLine = line;
      int saveCol = col;
      String w = readIdent();
      if (w.equalsIgnoreCase("not")) {
        negated = true;
        skipInline();
      } else {
        pos = savePos;
        line = saveLine;
        col = saveCol;
      }
    }
    List<String> path = readPath();
    return new Cond(negated, path, condLine, condCol);
  }

  // ---- --- frontmatter header ----------------------------------------------

  private boolean atDashLine() {
    if (!s.startsWith("---", pos)) return false;
    int i = pos + 3;
    while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\r')) i++;
    return i >= s.length() || s.charAt(i) == '\n';
  }

  private void consumeDashLine() {
    advance(3); // ---
    while (peek() == ' ' || peek() == '\t') advance(1);
    if (peek() == '\r') advance(1);
    if (peek() == '\n') advance(1);
  }

  private void skipFront() {
    for (; ; ) {
      char c = peek();
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',') {
        advance(1);
        continue;
      }
      if (c == '#') {
        while (peek() != '\0' && peek() != '\n') advance(1);
        continue;
      }
      break;
    }
  }

  private void lexFrontmatter() {
    int fmLine = line;
    int fmCol = col;
    consumeDashLine();
    for (; ; ) {
      skipFront();
      if (peek() == '\0') throw error("unterminated frontmatter (--- … ---)", fmLine, fmCol);
      if (atDashLine()) {
        consumeDashLine();
        return;
      }
      int kwLine = line;
      int kwCol = col;
      String kw = readIdent().toLowerCase();
      if (kw.equals("params")) readFrontParams(kwLine, kwCol);
      else if (kw.equals("example")) readFrontExample(kwLine, kwCol);
      else throw error("unknown frontmatter section: " + kw, kwLine, kwCol);
    }
  }

  private void expectBrace(String what, int l, int c) {
    skipFront();
    if (peek() != '{') throw error("expected '{' after " + what, l, c);
    advance(1);
  }

  private void readFrontParams(int l, int c) {
    expectBrace("params", l, c);
    List<ParamDecl> decls = new ArrayList<>();
    for (; ; ) {
      skipFront();
      if (peek() == '}') {
        advance(1);
        break;
      }
      if (peek() == '\0') throw error("unterminated params { … }", l, c);
      String name = readIdent();
      skipInline();
      if (peek() != ':') throw error("expected ':' after parameter '" + name + "'");
      advance(1);
      skipInline();
      decls.add(new ParamDecl(name, readTypeExpr()));
    }
    tokens.add(new ParamsTok(decls, l, c));
  }

  private void readFrontExample(int l, int c) {
    skipInline();
    if (!isIdentStart(peek())) throw error("expected an example id", l, c);
    StringBuilder id = new StringBuilder();
    while (isSlugChar(peek())) id.append(advance(1));
    skipInline();
    String description = null;
    if (peek() == '"') description = readQuotedString();
    expectBrace("example", l, c);
    Map<String, ExampleValue> bindings = new LinkedHashMap<>();
    for (; ; ) {
      skipFront();
      if (peek() == '}') {
        advance(1);
        break;
      }
      if (peek() == '\0') throw error("unterminated example { … }", l, c);
      String name = readIdent();
      skipInline();
      if (peek() != ':') throw error("expected ':' after '" + name + "' in example");
      advance(1);
      bindings.put(name, readExampleValue());
    }
    tokens.add(new ExampleTok(id.toString(), description, bindings, l, c));
  }

  private TypeExpr readTypeExpr() {
    TypeBase base = readTypeBase();
    boolean array = false;
    boolean optional = false;
    Integer min = null;
    Integer max = null;
    if (peek() == '[' && peek(1) == ']') {
      advance(2);
      array = true;
    }
    for (; ; ) {
      skipInline();
      if (!isIdentStart(peek())) break;
      int savePos = pos;
      int saveLine = line;
      int saveCol = col;
      String word = readIdent().toLowerCase();
      if (word.equals("optional")) {
        optional = true;
      } else if (word.equals("min") || word.equals("max")) {
        if (!array) throw error("min/max apply only to arrays ([])");
        skipInline();
        int n = readInt();
        if (word.equals("min")) min = n;
        else max = n;
      } else {
        pos = savePos;
        line = saveLine;
        col = saveCol;
        break;
      }
    }
    return new TypeExpr(base, array, optional, min, max);
  }

  private TypeBase readTypeBase() {
    if (peek() == '{') return readRecordType();
    int idLine = line;
    int idCol = col;
    String ident = readIdent();
    String low = ident.toLowerCase();
    if (low.equals("literal")) {
      if (peek() != '(') throw error("expected '(' after literal");
      advance(1);
      String datatype = readDatatypeRef();
      if (peek() != ')') throw error("expected ')' after literal datatype");
      advance(1);
      return ScalarType.literal(datatype);
    }
    if (SCALARS.contains(low)) {
      return ScalarType.of(scalarKind(low));
    }
    if (TypeRegistry.has(low)) return ScalarType.custom(low);
    throw error("unknown type: " + ident, idLine, idCol);
  }

  private TypeBase readRecordType() {
    advance(1); // {
    Map<String, TypeExpr> fields = new LinkedHashMap<>();
    for (; ; ) {
      skipWs();
      if (peek() == '}') {
        advance(1);
        break;
      }
      if (peek() == '\0') throw error("unterminated record type");
      String name = readIdent();
      skipInline();
      if (peek() != ':') throw error("expected ':' after field '" + name + "'");
      advance(1);
      skipInline();
      fields.put(name, readTypeExpr());
    }
    return new Ast.RecordType(fields);
  }

  // ---- example values (RDF term literals) ----------------------------------

  private ExampleValue readExampleValue() {
    skipInline();
    char ch = peek();
    if (ch == '<') {
      String iri = readDatatypeRef();
      return new Ast.IriVal(iri.substring(1, iri.length() - 1));
    }
    if (ch == '"') {
      String value = readQuotedString();
      if (peek() == '@') {
        advance(1);
        StringBuilder lang = new StringBuilder();
        while (isLangChar(peek())) lang.append(advance(1));
        return new Ast.StringVal(value, lang.toString(), null);
      }
      if (peek() == '^' && peek(1) == '^') {
        advance(2);
        return new Ast.StringVal(value, null, readDatatypeRef());
      }
      return new Ast.StringVal(value, null, null);
    }
    if (ch == '[') return readExampleList();
    if (ch == '{') return readExampleRecord();
    if (ch == '-' || isDigit(ch)) {
      StringBuilder num = new StringBuilder();
      if (ch == '-') num.append(advance(1));
      while (isDigit(peek()) || ".eE+-".indexOf(peek()) >= 0) num.append(advance(1));
      return new Ast.NumberVal(parseNumber(num.toString()));
    }
    if (isLetter(ch)) {
      String word = readIdent();
      if (word.equals("true") || word.equals("false")) return new Ast.BoolVal(word.equals("true"));
      if (peek() == ':') {
        advance(1);
        StringBuilder local = new StringBuilder();
        while (peek() != '\0' && isDatatypePnameChar(peek()) && peek() != ':') local.append(advance(1));
        return new Ast.PnameVal(word, local.toString());
      }
      throw error("invalid example value starting with '" + word + "'");
    }
    throw error("expected an example value");
  }

  private static Object parseNumber(String num) {
    if (num.indexOf('.') < 0 && num.indexOf('e') < 0 && num.indexOf('E') < 0) {
      try {
        return Long.parseLong(num);
      } catch (NumberFormatException ignored) {
        // fall through to double
      }
    }
    return Double.parseDouble(num);
  }

  private ExampleValue readExampleList() {
    advance(1); // [
    List<ExampleValue> items = new ArrayList<>();
    for (; ; ) {
      skipWs();
      if (peek() == ']') {
        advance(1);
        break;
      }
      if (peek() == '\0') throw error("unterminated example list");
      items.add(readExampleValue());
    }
    return new Ast.ListVal(items);
  }

  private ExampleValue readExampleRecord() {
    advance(1); // {
    Map<String, ExampleValue> fields = new LinkedHashMap<>();
    for (; ; ) {
      skipWs();
      if (peek() == '}') {
        advance(1);
        break;
      }
      if (peek() == '\0') throw error("unterminated example record");
      String name = readIdent();
      skipInline();
      if (peek() != ':') throw error("expected ':' after field '" + name + "'");
      advance(1);
      fields.put(name, readExampleValue());
    }
    return new Ast.RecordVal(fields);
  }
}
