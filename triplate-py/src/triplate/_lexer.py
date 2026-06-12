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
_WS_COMMA = re.compile(r"[\s,]")
_DT_LOCAL = re.compile(r"[A-Za-z0-9_.:-]")
_PN_LOCAL = re.compile(r"[A-Za-z0-9_.-]")


def _is(rx, c):
    return bool(c) and bool(rx.match(c))


def tokenize(source):
    return _Lexer(source).run()


class _Lexer:
    def __init__(self, source):
        self.s = source
        self.pos = 0
        self.line = 1
        self.col = 1
        self.tokens = []
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
            elif ch == "#":
                self._lex_comment_as_text()
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

    def _skip_ws(self):
        while self._peek() != "" and _WS_COMMA.match(self._peek()):
            self._advance(1)

    def _enter_string(self, quote):
        triple = quote * 3
        if self.s.startswith(triple, self.pos):
            self.string_delim = triple
            self._take(3)
        else:
            self.string_delim = quote
            self._take(1)

    def _lex_comment_as_text(self):
        end = self.s.find("\n", self.pos)
        self._take((len(self.s) if end < 0 else end) - self.pos)

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

    # ---- value constructs --------------------------------------------------

    def _lex_value(self):
        line, col = self.line, self.col
        self._flush()
        self._advance(2)  # ${
        self._skip_inline()
        path = self._read_path()
        self._skip_inline()
        if self._peek() != "}":
            self._error("unterminated ${ … }", line, col)
        self._advance(1)
        self.tokens.append({"kind": "value", "path": path, "line": line, "column": col})

    def _read_hole(self):
        line, col = self.line, self.col
        self._advance(2)  # ${
        self._skip_inline()
        path = self._read_path()
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
            self.tokens.append({"kind": "for", **f, "line": line, "column": col})
        elif keyword == "endfor":
            self._end_tag("endfor")
            self.tokens.append({"kind": "endfor", "line": line, "column": col})
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

    def _read_for_header(self):
        self._skip_inline()
        item = self._read_ident()
        self._skip_inline()
        if self._read_ident().lower() != "in":
            self._error("expected 'in' in %for")
        self._skip_inline()
        source = self._read_path()
        join = None
        join_exact = False
        seen_join = False
        while True:
            self._skip_inline()
            if self._at_tag_end() or not _is(_IDENT_START, self._peek()):
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
                self._error(f"unexpected token in %for: {word}")
        self._skip_inline()
        if not self._at_tag_end():
            self._error("unexpected content in %for directive")
        self._advance(2)
        return {"item": item, "source": source, "join": join, "join_exact": join_exact}

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
        path = self._read_path()
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
        while True:
            c = self._peek()
            if c in (" ", "\t", "\n", "\r", ","):
                self._advance(1)
                continue
            if c == "#":
                while self._peek() not in ("", "\n"):
                    self._advance(1)
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
            name = self._read_ident()
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
            name = self._read_ident()
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
            datatype = self._read_datatype_ref()
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
            self._skip_ws()
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
        if ch == "<":
            return ExIri(self._read_datatype_ref()[1:-1])
        if ch == '"':
            value = self._read_quoted_string()
            if self._peek() == "@":
                self._advance(1)
                lang = []
                while _is(_LANG_CHAR, self._peek()):
                    lang.append(self._advance(1))
                return ExString(value, lang="".join(lang))
            if self._peek() == "^" and self._peek(1) == "^":
                self._advance(2)
                return ExString(value, datatype=self._read_datatype_ref())
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
                return ExPname(word, "".join(local))
            self._error(f"invalid example value starting with '{word}'")
        self._error("expected an example value")

    def _read_example_list(self):
        self._advance(1)  # [
        items = []
        while True:
            self._skip_ws()
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
            self._skip_ws()
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
