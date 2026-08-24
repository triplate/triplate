"""Single-pass tokenizer. Mirrors the TypeScript implementation's lexer.ts."""

import re

from ._ast import (
    Cond,
    ExBoolean,
    ExIri,
    ExList,
    ExNumber,
    ExPname,
    ExRecord,
    ExString,
    LangPath,
    LangStatic,
    ParamDecl,
    PartHole,
    PartText,
    TemplateSymbol,
    TypeExpr,
)
from ._registry import has_custom_type
from .errors import TriplateSyntaxError

SCALARS = {
    "iri", "pname", "string", "int", "decimal", "double", "bool",
    "date", "datetime", "time", "literal", "term", "raw",
}
IRI_BODY = re.compile(r'^[^\u0000-\u0020<>"{}|^`\\]+$')
PNAME_TOKEN = re.compile(
    r"^(?:[A-Za-z_][A-Za-z0-9_-]*)?:(?:[A-Za-z0-9_](?:[A-Za-z0-9_.-]*[A-Za-z0-9_-])?)?$"
)
ESCAPES = {"\\": "\\", '"': '"', "n": "\n", "r": "\r", "t": "\t"}

_IDENT_START = re.compile(r"[A-Za-z_]")
_IDENT_CHAR = re.compile(r"[A-Za-z0-9_]")
_SLUG_CHAR = re.compile(r"[A-Za-z0-9_-]")
_LETTER = re.compile(r"[A-Za-z]")
_LANG_CHAR = re.compile(r"[A-Za-z0-9-]")
_DT_LOCAL = re.compile(r"[A-Za-z0-9_.:-]")
_PN_LOCAL = re.compile(r"[A-Za-z0-9_.-]")


def _is(rx, c):
    return bool(c) and bool(rx.match(c))


def tokenize(source):
    return _Lexer(source).run()


def lex(source):
    """Tokenize and collect positioned source symbols in a single pass."""
    lexer = _Lexer(source)
    tokens = lexer.run()
    return tokens, lexer.symbols


def extract_symbols(source):
    """Lenient symbol extraction for IDE features over possibly-malformed
    templates: returns the positioned symbols collected up to the first syntax
    error (i.e. "what parsed") instead of raising.
    """
    lexer = _Lexer(source)
    try:
        lexer.run()
    except TriplateSyntaxError:
        pass  # Return what was collected before the failure.
    return lexer.symbols


class _Lexer:
    def __init__(self, source):
        self.s = source
        self.pos = 0
        self.line = 1
        self.col = 1
        self.tokens = []
        # Positioned source symbols, accumulated in source order. self.pos is an
        # absolute, 0-based code-point offset, so it doubles as the symbol offset.
        self.symbols = []
        # Active {% for %} bindings, innermost last: a (name, id) tuple per loop.
        # A body reference whose root matches an entry (searched innermost-to-
        # outermost for shadowing) is a loopRef carrying that entry's scope id.
        self.scopes = []
        # Monotonic id source: every loopDecl gets a fresh, unique scope id.
        self.next_scope = 0
        self.buf = []
        self.line_has_content = False
        self.string_delim = None

    def run(self):
        s = self.s
        if self._at_dash_line():
            self._lex_frontmatter()
        while self.pos < len(s):
            ch = s[self.pos]
            if self.string_delim is not None:
                if s.startswith(self.string_delim, self.pos):
                    self._take(len(self.string_delim))
                    self.string_delim = None
                elif len(self.string_delim) == 1 and ch == "\\" and self.pos + 1 < len(s):
                    self._take(2)
                elif len(self.string_delim) == 1 and ch == "\n":
                    self.string_delim = None
                    self._take(1)
                else:
                    self._take(1)
                continue
            nxt = self._peek(1)
            if ch == "$" and nxt == "{":
                self._lex_value()
            elif ch == "$" and nxt == '"':
                self._lex_interp_string()
            elif ch == "$" and nxt == "<":
                self._lex_iri_template()
            elif ch == "{" and nxt == "%":
                self._lex_tag()
            elif ch in ('"', "'"):
                self._enter_string(ch)
            elif ch == "<":
                self._try_iri_ref()
            else:
                self._take(1)
        self._flush()
        return self.tokens

    # ---- primitives --------------------------------------------------------

    def _peek(self, o=0):
        i = self.pos + o
        return self.s[i] if i < len(self.s) else ""

    def _advance(self, n=1):
        out = self.s[self.pos : self.pos + n]
        for c in out:
            if c == "\n":
                self.line += 1
                self.col = 1
                self.line_has_content = False
            else:
                self.col += 1
                if c not in (" ", "\t", "\r"):
                    self.line_has_content = True
        self.pos += n
        return out

    def _take(self, n):
        self.buf.append(self._advance(n))

    def _flush(self):
        if self.buf:
            self.tokens.append({"kind": "text", "value": "".join(self.buf)})
            self.buf = []

    def _error(self, message, line=None, column=None):
        raise TriplateSyntaxError(
            message, self.line if line is None else line, self.col if column is None else column
        )

    def _skip_inline(self):
        while self._peek() in (" ", "\t"):
            self._advance(1)

    def _enter_string(self, quote):
        triple = quote * 3
        if self.s.startswith(triple, self.pos):
            self.string_delim = triple
            self._take(3)
        else:
            self.string_delim = quote
            self._take(1)

    def _try_iri_ref(self):
        i = self.pos + 1
        s = self.s
        while i < len(s) and s[i] not in ' \t\n\r<>"':
            i += 1
        if i < len(s) and s[i] == ">":
            self._take(i + 1 - self.pos)
        else:
            self._take(1)

    def _read_ident(self):
        if not _is(_IDENT_START, self._peek()):
            self._error("expected an identifier")
        out = []
        while _is(_IDENT_CHAR, self._peek()):
            out.append(self._advance(1))
        return "".join(out)

    def _read_path(self):
        parts = [self._read_ident()]
        while self._peek() == "." and _is(_IDENT_START, self._peek(1)):
            self._advance(1)
            parts.append(self._read_ident())
        return tuple(parts)

    def _read_int(self):
        if not self._peek().isdigit():
            self._error("expected an int")
        d = []
        while self._peek().isdigit():
            d.append(self._advance(1))
        return int("".join(d))

    def _read_quoted_string(self):
        if self._peek() != '"':
            self._error("expected a quoted string")
        self._advance(1)
        out = []
        while True:
            ch = self._peek()
            if ch == "" or ch == "\n":
                self._error("unterminated quoted string")
            if ch == '"':
                self._advance(1)
                return "".join(out)
            if ch == "\\":
                e = self._peek(1)
                if e not in ESCAPES:
                    self._error(f"invalid escape \\{e}")
                self._advance(2)
                out.append(ESCAPES[e])
                continue
            out.append(self._advance(1))

    def _read_datatype_ref(self):
        if self._peek() == "<":
            self._advance(1)
            body = []
            while self._peek() not in (">", "", "\n"):
                body.append(self._advance(1))
            if self._peek() != ">":
                self._error("unterminated IRI reference")
            self._advance(1)
            text = "".join(body)
            if not IRI_BODY.match(text):
                self._error(f"invalid IRI reference: <{text}>")
            return f"<{text}>"
        tok = []
        while self._peek() != "" and _DT_LOCAL.match(self._peek()):
            tok.append(self._advance(1))
        text = "".join(tok)
        if not PNAME_TOKEN.match(text):
            self._error(f"invalid prefixed name: {text}")
        return text

    # ---- positioned symbols ------------------------------------------------

    def _emit_ref(self, root_start, path):
        """Emit a body reference symbol for the root segment of a ${…}/{% … %} path.

        If the root binds to an in-scope loop variable (searched innermost-to-
        outermost for shadowing) it is a loopRef carrying that binding's scope id;
        otherwise it is a paramRef.
        """
        root = path[0]
        end = root_start + len(root)
        for name, scope_id in reversed(self.scopes):
            if name == root:
                self.symbols.append(TemplateSymbol("loopRef", root_start, end, name=root, scope=scope_id))
                return
        self.symbols.append(TemplateSymbol("paramRef", root_start, end, name=root))

    def _emit_datatype_ref(self, start, end, dt):
        """Emit a datatype reference (`<iri>` or `prefix:local`) as an iri/pname symbol."""
        if dt.startswith("<"):
            self.symbols.append(TemplateSymbol("iri", start, end, value=dt[1:-1]))
        else:
            i = dt.find(":")
            self.symbols.append(TemplateSymbol("pname", start, end, prefix=dt[:i], local=dt[i + 1 :]))

    # ---- value constructs --------------------------------------------------

    def _lex_value(self):
        line, col = self.line, self.col
        self._flush()
        self._advance(2)  # ${
        self._skip_inline()
        spread = False
        if self._peek() == "." and self._peek(1) == "." and self._peek(2) == ".":
            self._advance(3)
            spread = True
            self._skip_inline()
        root_start = self.pos
        path = self._read_path()
        self._emit_ref(root_start, path)
        join = None
        join_exact = False
        if spread:
            join, join_exact = self._read_join_clause(lambda: self._peek() == "}", "${ … }")
        self._skip_inline()
        if self._peek() != "}":
            self._error("unterminated ${ … }", line, col)
        self._advance(1)
        self.tokens.append(
            {"kind": "value", "path": path, "spread": spread, "join": join, "join_exact": join_exact, "line": line, "column": col}
        )

    def _read_hole(self):
        line, col = self.line, self.col
        self._advance(2)  # ${
        self._skip_inline()
        root_start = self.pos
        path = self._read_path()
        self._emit_ref(root_start, path)
        self._skip_inline()
        if self._peek() != "}":
            self._error("unterminated ${ … } hole", line, col)
        self._advance(1)
        return PartHole(path, line, col)

    def _lex_interp_string(self):
        line, col = self.line, self.col
        self._flush()
        self._advance(2)  # $"
        parts = []
        text = []

        def flush():
            if text:
                parts.append(PartText("".join(text)))
                del text[:]

        while True:
            ch = self._peek()
            if ch == "" or ch == "\n":
                self._error('unterminated $"…" string literal', line, col)
            if ch == '"':
                self._advance(1)
                break
            if ch == "\\":
                e = self._peek(1)
                if e not in ESCAPES:
                    self._error(f'invalid escape \\{e} in $"…"')
                self._advance(2)
                text.append(ESCAPES[e])
                continue
            if ch == "$" and self._peek(1) == "{":
                flush()
                parts.append(self._read_hole())
                continue
            text.append(self._advance(1))
        flush()
        lang = None
        datatype = None
        if self._peek() == "@":
            self._advance(1)
            if self._peek() == "$" and self._peek(1) == "{":
                lang = LangPath(self._read_hole().path)
            else:
                if not _is(_LETTER, self._peek()):
                    self._error("expected a language tag after @")
                tag = []
                while _is(_LANG_CHAR, self._peek()):
                    tag.append(self._advance(1))
                lang = LangStatic("".join(tag))
        elif self._peek() == "^" and self._peek(1) == "^":
            self._advance(2)
            datatype = self._read_datatype_ref()
        self.tokens.append(
            {"kind": "interp", "parts": tuple(parts), "lang": lang, "datatype": datatype, "line": line, "column": col}
        )

    def _lex_iri_template(self):
        line, col = self.line, self.col
        self._flush()
        self._advance(2)  # $<
        parts = []
        text = []

        def flush():
            if text:
                parts.append(PartText("".join(text)))
                del text[:]

        while True:
            ch = self._peek()
            if ch == "" or ch == "\n":
                self._error("unterminated $<…> IRI template", line, col)
            if ch == ">":
                self._advance(1)
                break
            if ch == "$" and self._peek(1) == "{":
                flush()
                parts.append(self._read_hole())
                continue
            text.append(self._advance(1))
        flush()
        self.tokens.append({"kind": "iri", "parts": tuple(parts), "line": line, "column": col})

    # ---- {% … %} tags ------------------------------------------------------

    def _lex_tag(self):
        line, col = self.line, self.col
        line_clean = not self.line_has_content
        if line_clean and self.buf:
            self.buf = [re.sub(r"[ \t]+$", "", "".join(self.buf))]
        self._flush()
        self._advance(2)  # {%
        self._skip_inline()
        if not _is(_IDENT_START, self._peek()):
            self._error("expected a directive keyword")
        keyword = self._read_ident().lower()
        if keyword == "for":
            f = self._read_for_header()
            scope = f.pop("scope")
            self.tokens.append({"kind": "for", **f, "line": line, "column": col})
            # Push the new binding only after the header (its source ref already
            # resolved against the outer scopes) so the loop body sees `item`.
            self.scopes.append((f["item"], scope))
        elif keyword == "endfor":
            self._end_tag("endfor")
            self.tokens.append({"kind": "endfor", "line": line, "column": col})
            # Pop the innermost binding; tolerant of malformed input (no open
            # for) under the lenient extract_symbols reader.
            if self.scopes:
                self.scopes.pop()
        elif keyword == "if":
            cond = self._read_cond()
            self.tokens.append({"kind": "if", "cond": cond, "line": line, "column": col})
            self._end_tag("if")
        elif keyword == "elif":
            cond = self._read_cond()
            self.tokens.append({"kind": "elif", "cond": cond, "line": line, "column": col})
            self._end_tag("elif")
        elif keyword == "else":
            self._end_tag("else")
            self.tokens.append({"kind": "else", "line": line, "column": col})
        elif keyword == "endif":
            self._end_tag("endif")
            self.tokens.append({"kind": "endif", "line": line, "column": col})
        else:
            self._error(f"unknown directive: {keyword}", line, col)
        if line_clean:
            self._trim_trailing_newline()

    def _at_tag_end(self):
        return self._peek() == "%" and self._peek(1) == "}"

    def _end_tag(self, what):
        self._skip_inline()
        if not self._at_tag_end():
            self._error(f"unexpected content in %{what} directive")
        self._advance(2)

    def _trim_trailing_newline(self):
        if self._peek() == "\r":
            self._advance(1)
        if self._peek() == "\n":
            self._advance(1)

    def _read_join_clause(self, at_end, context):
        """Parse an optional `join "<sep>" [explicit]` clause, stopping at at_end()."""
        join = None
        join_exact = False
        seen_join = False
        while True:
            self._skip_inline()
            if at_end() or not _is(_IDENT_START, self._peek()):
                break
            word = self._read_ident().lower()
            if word == "join":
                if seen_join:
                    self._error("duplicate join")
                self._skip_inline()
                join = self._read_quoted_string()
                seen_join = True
            elif word == "explicit":
                if not seen_join:
                    self._error("'explicit' requires a preceding join")
                join_exact = True
            else:
                self._error(f"unexpected token in {context}: {word}")
        return join, join_exact

    def _read_for_header(self):
        self._skip_inline()
        item_start = self.pos
        item = self._read_ident()
        self._skip_inline()
        if self._read_ident().lower() != "in":
            self._error("expected 'in' in %for")
        self._skip_inline()
        root_start = self.pos
        source = self._read_path()
        # Emit the `item` binding (earlier in source) before the source ref to
        # keep symbols in ascending order. The new scope is not pushed until
        # _lex_tag, so a loop iterating an outer loop variable still resolves
        # against the outer bindings here.
        scope = self.next_scope
        self.next_scope += 1
        self.symbols.append(
            TemplateSymbol("loopDecl", item_start, item_start + len(item), name=item, scope=scope)
        )
        self._emit_ref(root_start, source)
        join, join_exact = self._read_join_clause(self._at_tag_end, "%for")
        self._skip_inline()
        if not self._at_tag_end():
            self._error("unexpected content in %for directive")
        self._advance(2)
        return {"item": item, "source": source, "scope": scope, "join": join, "join_exact": join_exact}

    def _read_cond(self):
        self._skip_inline()
        line, col = self.line, self.col
        negated = False
        if _is(_IDENT_START, self._peek()):
            before = (self.pos, self.line, self.col)
            w = self._read_ident()
            if w.lower() == "not":
                negated = True
                self._skip_inline()
            else:
                self.pos, self.line, self.col = before
        root_start = self.pos
        path = self._read_path()
        self._emit_ref(root_start, path)
        return Cond(negated, path, line, col)

    # ---- --- frontmatter header --------------------------------------------

    def _at_dash_line(self):
        if not self.s.startswith("---", self.pos):
            return False
        i = self.pos + 3
        while i < len(self.s) and self.s[i] in (" ", "\t", "\r"):
            i += 1
        return i >= len(self.s) or self.s[i] == "\n"

    def _consume_dash_line(self):
        self._advance(3)  # ---
        while self._peek() in (" ", "\t"):
            self._advance(1)
        if self._peek() == "\r":
            self._advance(1)
        if self._peek() == "\n":
            self._advance(1)

    def _skip_front(self):
        """Whitespace (incl. newlines), commas and ``#`` comments between
        frontmatter items -- at every nesting depth, so a record type, example
        list or example record can carry comments too. The sole skipper inside
        the header, which is what keeps the three implementations compatible.
        """
        while True:
            c = self._peek()
            if c in (" ", "\t", "\n", "\r", ","):
                self._advance(1)
                continue
            if c == "#":
                start = self.pos
                while self._peek() not in ("", "\n"):
                    self._advance(1)
                self.symbols.append(
                    TemplateSymbol("comment", start, self.pos, value=self.s[start : self.pos])
                )
                continue
            break

    def _lex_frontmatter(self):
        fm_line, fm_col = self.line, self.col
        self._consume_dash_line()
        while True:
            self._skip_front()
            if self._peek() == "":
                self._error("unterminated frontmatter (--- … ---)", fm_line, fm_col)
            if self._at_dash_line():
                self._consume_dash_line()
                return
            line, col = self.line, self.col
            kw = self._read_ident().lower()
            if kw == "params":
                self._read_front_params(line, col)
            elif kw == "example":
                self._read_front_example(line, col)
            else:
                self._error(f"unknown frontmatter section: {kw}", line, col)

    def _expect_brace(self, what, line, col):
        self._skip_front()
        if self._peek() != "{":
            self._error(f"expected '{{' after {what}", line, col)
        self._advance(1)

    def _read_front_params(self, line, col):
        self._expect_brace("params", line, col)
        decls = []
        while True:
            self._skip_front()
            if self._peek() == "}":
                self._advance(1)
                break
            if self._peek() == "":
                self._error("unterminated params { … }", line, col)
            name_start = self.pos
            name = self._read_ident()
            self.symbols.append(TemplateSymbol("paramDecl", name_start, self.pos, name=name))
            self._skip_inline()
            if self._peek() != ":":
                self._error(f"expected ':' after parameter '{name}'")
            self._advance(1)
            self._skip_inline()
            decls.append(ParamDecl(name, self._read_type_expr()))
        self.tokens.append({"kind": "params", "decls": decls, "line": line, "column": col})

    def _read_front_example(self, line, col):
        self._skip_inline()
        if not _is(_IDENT_START, self._peek()):
            self._error("expected an example id", line, col)
        eid = []
        while _is(_SLUG_CHAR, self._peek()):
            eid.append(self._advance(1))
        self._skip_inline()
        description = None
        if self._peek() == '"':
            description = self._read_quoted_string()
        self._expect_brace("example", line, col)
        bindings = {}
        while True:
            self._skip_front()
            if self._peek() == "}":
                self._advance(1)
                break
            if self._peek() == "":
                self._error("unterminated example { … }", line, col)
            name_start = self.pos
            name = self._read_ident()
            self.symbols.append(TemplateSymbol("bindingKey", name_start, self.pos, name=name))
            self._skip_inline()
            if self._peek() != ":":
                self._error(f"expected ':' after '{name}' in example")
            self._advance(1)
            bindings[name] = self._read_example_value()
        self.tokens.append(
            {"kind": "examples", "id": "".join(eid), "description": description, "bindings": bindings, "line": line, "column": col}
        )

    def _read_type_expr(self):
        base = self._read_type_base()
        array = False
        optional = False
        mn = None
        mx = None
        if self._peek() == "[" and self._peek(1) == "]":
            self._advance(2)
            array = True
        while True:
            self._skip_inline()
            if not _is(_IDENT_START, self._peek()):
                break
            before = (self.pos, self.line, self.col)
            word = self._read_ident().lower()
            if word == "optional":
                optional = True
            elif word in ("min", "max"):
                if not array:
                    self._error("min/max apply only to arrays ([])")
                self._skip_inline()
                n = self._read_int()
                if word == "min":
                    mn = n
                else:
                    mx = n
            else:
                self.pos, self.line, self.col = before
                break
        return TypeExpr(base, array, optional, mn, mx)

    def _read_type_base(self):
        if self._peek() == "{":
            return self._read_record_type()
        line, col = self.line, self.col
        ident = self._read_ident()
        low = ident.lower()
        if low == "literal":
            if self._peek() != "(":
                self._error("expected '(' after literal")
            self._advance(1)
            dt_start = self.pos
            datatype = self._read_datatype_ref()
            self._emit_datatype_ref(dt_start, self.pos, datatype)
            if self._peek() != ")":
                self._error("expected ')' after literal datatype")
            self._advance(1)
            return {"kind": "literal", "datatype": datatype}
        if low in SCALARS:
            return {"kind": "dateTime" if low == "datetime" else low}
        if has_custom_type(low):
            return {"kind": "custom", "name": low}
        self._error(f"unknown type: {ident}", line, col)

    def _read_record_type(self):
        self._advance(1)  # {
        fields = {}
        while True:
            self._skip_front()
            if self._peek() == "}":
                self._advance(1)
                break
            if self._peek() == "":
                self._error("unterminated record type")
            name = self._read_ident()
            self._skip_inline()
            if self._peek() != ":":
                self._error(f"expected ':' after field '{name}'")
            self._advance(1)
            self._skip_inline()
            fields[name] = self._read_type_expr()
        return {"kind": "record", "fields": fields}

    # ---- example values (RDF term literals) --------------------------------

    def _read_example_value(self):
        self._skip_inline()
        ch = self._peek()
        start = self.pos
        if ch == "<":
            value = self._read_datatype_ref()[1:-1]
            self.symbols.append(TemplateSymbol("iri", start, self.pos, value=value))
            return ExIri(value)
        if ch == '"':
            value = self._read_quoted_string()
            lit_end = self.pos
            if self._peek() == "@":
                self._advance(1)
                lang = []
                while _is(_LANG_CHAR, self._peek()):
                    lang.append(self._advance(1))
                self.symbols.append(TemplateSymbol("literal", start, lit_end, value=value))
                return ExString(value, lang="".join(lang))
            if self._peek() == "^" and self._peek(1) == "^":
                self._advance(2)
                dt_start = self.pos
                datatype = self._read_datatype_ref()
                # Push the literal (earlier offset) before its datatype ref to keep symbols ascending.
                self.symbols.append(TemplateSymbol("literal", start, lit_end, value=value, datatype=datatype))
                self._emit_datatype_ref(dt_start, self.pos, datatype)
                return ExString(value, datatype=datatype)
            self.symbols.append(TemplateSymbol("literal", start, lit_end, value=value))
            return ExString(value)
        if ch == "[":
            return self._read_example_list()
        if ch == "{":
            return self._read_example_record()
        if ch == "-" or ch.isdigit():
            num = self._advance(1) if ch == "-" else ""
            while self._peek().isdigit() or self._peek() in ".eE+-":
                num += self._advance(1)
            return ExNumber(float(num) if any(c in num for c in ".eE") else int(num))
        if _is(_LETTER, ch):
            word = self._read_ident()
            if word in ("true", "false"):
                return ExBoolean(word == "true")
            if self._peek() == ":":
                self._advance(1)
                local = []
                while self._peek() != "" and _PN_LOCAL.match(self._peek()):
                    local.append(self._advance(1))
                local_str = "".join(local)
                self.symbols.append(TemplateSymbol("pname", start, self.pos, prefix=word, local=local_str))
                return ExPname(word, local_str)
            self._error(f"invalid example value starting with '{word}'")
        self._error("expected an example value")

    def _read_example_list(self):
        self._advance(1)  # [
        items = []
        while True:
            self._skip_front()
            if self._peek() == "]":
                self._advance(1)
                break
            if self._peek() == "":
                self._error("unterminated example list")
            items.append(self._read_example_value())
        return ExList(tuple(items))

    def _read_example_record(self):
        self._advance(1)  # {
        fields = {}
        while True:
            self._skip_front()
            if self._peek() == "}":
                self._advance(1)
                break
            if self._peek() == "":
                self._error("unterminated example record")
            name = self._read_ident()
            self._skip_inline()
            if self._peek() != ":":
                self._error(f"expected ':' after field '{name}'")
            self._advance(1)
            fields[name] = self._read_example_value()
        return ExRecord(fields)
