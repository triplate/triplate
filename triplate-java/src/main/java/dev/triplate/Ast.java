package dev.triplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The compiled template model: declared types, the parsed body tree, and the
 * example sets. Mirrors {@code ast.ts} in the TypeScript reference port.
 */
public final class Ast {
  private Ast() {}

  /** Scalar serialization kinds (how a value is emitted). */
  public enum ScalarKind {
    IRI, PNAME, STRING, INT, DECIMAL, DOUBLE, BOOL, DATE, DATE_TIME, TIME, LITERAL, TERM, RAW, CUSTOM
  }

  /** A scalar or record type base. */
  public sealed interface TypeBase permits ScalarType, RecordType {}

  /** A scalar type; {@code datatype} is set for {@code literal(<dt>)}, {@code name} for custom types. */
  public record ScalarType(ScalarKind kind, String datatype, String name) implements TypeBase {
    public static ScalarType of(ScalarKind kind) {
      return new ScalarType(kind, null, null);
    }

    public static ScalarType literal(String datatype) {
      return new ScalarType(ScalarKind.LITERAL, datatype, null);
    }

    public static ScalarType custom(String name) {
      return new ScalarType(ScalarKind.CUSTOM, null, name);
    }
  }

  /** A record type: an ordered map of field name to declared type. */
  public record RecordType(Map<String, TypeExpr> fields) implements TypeBase {}

  /** A declared type: a base, optionally an array (with bounds), optionally optional. */
  public record TypeExpr(TypeBase base, boolean array, boolean optional, Integer min, Integer max) {
    /** The element type of an array (or the scalar variant of this type). */
    public TypeExpr elem() {
      return new TypeExpr(base, false, false, null, null);
    }
  }

  public record ParamDecl(String name, TypeExpr type) {}

  public record Schema(List<ParamDecl> params, Map<String, TypeExpr> byName) {}

  /** A language tag: static ({@code @en}) or dynamic ({@code @${lang}}). */
  public sealed interface LangSpec permits StaticLang, PathLang {}

  public record StaticLang(String tag) implements LangSpec {}

  public record PathLang(List<String> path) implements LangSpec {}

  /** A part of an interpolated string or IRI template: literal text or a {@code ${ }} hole. */
  public sealed interface Part permits TextPart, HolePart {}

  public record TextPart(String text) implements Part {}

  public record HolePart(List<String> path, int line, int column) implements Part {}

  public record Cond(boolean negated, List<String> path, int line, int column) {}

  /** A node in the body tree. */
  public sealed interface Node permits TextNode, ValueNode, InterpNode, IriNode, ForNode, IfNode {}

  public record TextNode(String value) implements Node {}

  public record ValueNode(List<String> path, int line, int column) implements Node {}

  public record InterpNode(List<Part> parts, LangSpec lang, String datatype, int line, int column)
      implements Node {}

  public record IriNode(List<Part> parts, int line, int column) implements Node {}

  /** A {@code {% for %}} block. The body is filled in while building the tree. */
  public static final class ForNode implements Node {
    public final String item;
    public final List<String> source;
    public final String join; // null = no separator
    public final boolean joinExact;
    public final List<Node> body = new ArrayList<>();
    public final int line;
    public final int column;

    public ForNode(String item, List<String> source, String join, boolean joinExact, int line, int column) {
      this.item = item;
      this.source = source;
      this.join = join;
      this.joinExact = joinExact;
      this.line = line;
      this.column = column;
    }
  }

  /** One branch of an {@code {% if %}}/{@code {% elif %}} chain. */
  public static final class Branch {
    public final Cond cond;
    public final List<Node> body = new ArrayList<>();

    public Branch(Cond cond) {
      this.cond = cond;
    }
  }

  /** A {@code {% if %}} block. Branches and the optional else body are filled in while building. */
  public static final class IfNode implements Node {
    public final List<Branch> branches = new ArrayList<>();
    public List<Node> elseBody; // null until an {% else %} is seen
    public final int line;
    public final int column;

    public IfNode(int line, int column) {
      this.line = line;
      this.column = column;
    }
  }

  /** An example value literal (RDF term syntax), resolved to host values at preview. */
  public sealed interface ExampleValue
      permits IriVal, PnameVal, StringVal, NumberVal, BoolVal, ListVal, RecordVal {}

  public record IriVal(String value) implements ExampleValue {}

  public record PnameVal(String prefix, String local) implements ExampleValue {}

  public record StringVal(String value, String lang, String datatype) implements ExampleValue {}

  /** {@code value} is a {@link Long} for integral literals, otherwise a {@link Double}. */
  public record NumberVal(Object value) implements ExampleValue {}

  public record BoolVal(boolean value) implements ExampleValue {}

  public record ListVal(List<ExampleValue> items) implements ExampleValue {}

  public record RecordVal(Map<String, ExampleValue> fields) implements ExampleValue {}

  public record ExampleSet(String id, String description, Map<String, ExampleValue> bindings) {}

  public record CompiledTemplateData(Schema schema, List<ExampleSet> examples, List<Node> body) {}
}
