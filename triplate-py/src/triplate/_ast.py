"""AST and schema types. Mirrors the TypeScript implementation's ast.ts."""

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple, Union

RefPath = Tuple[str, ...]

# A scalar/record "base" is a dict with a 'kind' key:
#   {'kind': 'iri' | 'pname' | 'string' | ... | 'term' | 'raw'}
#   {'kind': 'literal', 'datatype': str}
#   {'kind': 'custom', 'name': str}
#   {'kind': 'record', 'fields': Dict[str, TypeExpr]}


@dataclass
class TypeExpr:
    base: dict
    array: bool = False
    optional: bool = False
    min: Optional[int] = None
    max: Optional[int] = None


@dataclass
class ParamDecl:
    name: str
    type: TypeExpr


@dataclass
class Schema:
    params: List[ParamDecl]
    by_name: Dict[str, TypeExpr]


@dataclass(frozen=True)
class LangStatic:
    static: str


@dataclass(frozen=True)
class LangPath:
    path: RefPath


LangSpec = Union[LangStatic, LangPath]


@dataclass(frozen=True)
class PartText:
    text: str


@dataclass(frozen=True)
class PartHole:
    path: RefPath
    line: int
    column: int


Part = Union[PartText, PartHole]


@dataclass(frozen=True)
class TextNode:
    value: str


@dataclass(frozen=True)
class ValueNode:
    path: RefPath
    line: int
    column: int
    spread: bool = False
    join: Optional[str] = None
    join_exact: bool = False


@dataclass(frozen=True)
class InterpNode:
    parts: Tuple[Part, ...]
    lang: Optional[LangSpec]
    datatype: Optional[str]
    line: int
    column: int


@dataclass(frozen=True)
class IriNode:
    parts: Tuple[Part, ...]
    line: int
    column: int


@dataclass(frozen=True)
class Cond:
    negated: bool
    path: RefPath
    line: int
    column: int


@dataclass
class ForNode:
    item: str
    source: RefPath
    join: Optional[str]
    join_exact: bool
    body: List["Node"] = field(default_factory=list)
    line: int = 0
    column: int = 0


@dataclass
class Branch:
    cond: Cond
    body: List["Node"] = field(default_factory=list)


@dataclass
class IfNode:
    branches: List[Branch]
    else_body: Optional[List["Node"]]
    line: int
    column: int


Node = Union[TextNode, ValueNode, InterpNode, IriNode, ForNode, IfNode]


# Example values (RDF term literals)
@dataclass(frozen=True)
class ExIri:
    value: str


@dataclass(frozen=True)
class ExPname:
    prefix: str
    local: str


@dataclass(frozen=True)
class ExString:
    value: str
    lang: Optional[str] = None
    datatype: Optional[str] = None


@dataclass(frozen=True)
class ExNumber:
    value: float


@dataclass(frozen=True)
class ExBoolean:
    value: bool


@dataclass(frozen=True)
class ExList:
    items: tuple


@dataclass(frozen=True)
class ExRecord:
    fields: dict


ExampleValue = Union[ExIri, ExPname, ExString, ExNumber, ExBoolean, ExList, ExRecord]


@dataclass
class ExampleSet:
    id: str
    description: Optional[str]
    bindings: Dict[str, ExampleValue]
    line: int = 0
    column: int = 0


@dataclass(frozen=True)
class TemplateSymbol:
    """A positioned source symbol — a flat, non-opaque view over the frontmatter
    and body references that drive IDE features (tooltips, prefix rename,
    parameter rename).

    ``kind`` is one of ``paramDecl``/``paramRef``/``bindingKey``/``loopDecl``/
    ``loopRef``/``pname``/``iri``/``literal``; only the fields relevant to that
    kind are populated (``name`` for params and loops; ``scope`` for
    ``loopDecl``/``loopRef``; ``prefix``/``local`` for ``pname``; ``value`` for
    ``iri``/``literal``; ``datatype`` optionally for ``literal``). ``start``
    and ``end`` are absolute, 0-based, end-exclusive code-point offsets into
    the original source.

    ``paramDecl`` (frontmatter ``params``), ``paramRef`` (every body
    ``${…}``/``{% … %}`` root reference) and ``bindingKey`` (frontmatter
    ``example`` keys) share a name space: grouping by ``name`` yields every
    rename site of a parameter. ``pname``/``iri``/``literal`` only ever occur
    in the frontmatter and feed the overlay (tooltips, prefix rename) and
    formatters.

    ``loopDecl`` (the ``item`` in a ``{% for item in … %}`` header) and
    ``loopRef`` (every body reference whose root segment resolves to an in-scope
    loop variable) are scoped, not name-spaced: each ``loopDecl`` carries a
    unique ``scope`` id and each ``loopRef`` carries the ``scope`` of the binding
    it resolves to, so grouping by ``scope`` yields every rename site of one loop
    variable (handling shadowing that grouping by ``name`` cannot). A reference
    that does not resolve to a loop variable stays a ``paramRef``.
    """

    kind: str
    start: int
    end: int
    name: Optional[str] = None
    scope: Optional[int] = None
    prefix: Optional[str] = None
    local: Optional[str] = None
    value: Optional[str] = None
    datatype: Optional[str] = None


@dataclass
class CompiledTemplateData:
    schema: Schema
    examples: List[ExampleSet]
    body: List[Node]
    symbols: List[TemplateSymbol] = field(default_factory=list)
