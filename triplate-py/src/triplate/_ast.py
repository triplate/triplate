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


@dataclass
class CompiledTemplateData:
    schema: Schema
    examples: List[ExampleSet]
    body: List[Node]
