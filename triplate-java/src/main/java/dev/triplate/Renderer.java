package dev.triplate;

import dev.triplate.Ast.Branch;
import dev.triplate.Ast.CompiledTemplateData;
import dev.triplate.Ast.Cond;
import dev.triplate.Ast.ForNode;
import dev.triplate.Ast.IfNode;
import dev.triplate.Ast.InterpNode;
import dev.triplate.Ast.IriNode;
import dev.triplate.Ast.LangSpec;
import dev.triplate.Ast.Node;
import dev.triplate.Ast.Part;
import dev.triplate.Ast.RecordType;
import dev.triplate.Ast.ScalarKind;
import dev.triplate.Ast.ScalarType;
import dev.triplate.Ast.Schema;
import dev.triplate.Ast.TypeExpr;
import dev.triplate.Ast.ValueNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Walks the compiled body tree with a context, producing the output string. Port of {@code renderer.ts}. */
final class Renderer {
  private Renderer() {}

  private record Bound(Object value, TypeExpr type) {}

  private record Resolved(Object value, TypeExpr type, boolean present) {}

  private static final class Env {
    final Schema schema;
    final Map<String, Object> context;
    final List<Map<String, Bound>> loop;

    Env(Schema schema, Map<String, Object> context, List<Map<String, Bound>> loop) {
      this.schema = schema;
      this.context = context;
      this.loop = loop;
    }
  }

  static String render(CompiledTemplateData data, Map<String, Object> context) {
    validateContext(data.schema(), context);
    return renderNodes(data.body(), new Env(data.schema(), context, new ArrayList<>()));
  }

  // ---- context validation (up front) --------------------------------------

  private static void validateContext(Schema schema, Map<String, Object> context) {
    for (String key : context.keySet()) {
      if (!schema.byName().containsKey(key)) {
        throw new TriplateBindingError("unknown parameter: " + key);
      }
    }
    for (Ast.ParamDecl decl : schema.params()) {
      boolean present = context.containsKey(decl.name());
      if (!present) {
        if (!decl.type().optional()) {
          throw new TriplateBindingError("missing required parameter: " + decl.name());
        }
        continue;
      }
      validateValue(decl.type(), context.get(decl.name()), decl.name());
    }
  }

  private static void validateValue(TypeExpr type, Object value, String label) {
    if (type.array()) {
      if (!(value instanceof List<?> list)) {
        throw new TriplateTypeError(label + " must be a list");
      }
      if (type.min() != null && list.size() < type.min()) {
        throw new TriplateCardinalityError(
            label + " requires at least " + type.min() + " item(s), got " + list.size());
      }
      if (type.max() != null && list.size() > type.max()) {
        throw new TriplateCardinalityError(
            label + " allows at most " + type.max() + " item(s), got " + list.size());
      }
      TypeExpr elem = type.elem();
      for (int i = 0; i < list.size(); i++) {
        validateValue(elem, list.get(i), label + "[" + i + "]");
      }
    } else if (type.base() instanceof RecordType rec) {
      if (!(value instanceof Map<?, ?> obj)) {
        throw new TriplateTypeError(label + " must be an object");
      }
      for (Object key : obj.keySet()) {
        if (!rec.fields().containsKey(key)) {
          throw new TriplateBindingError("unknown field: " + label + "." + key);
        }
      }
      for (Map.Entry<String, TypeExpr> e : rec.fields().entrySet()) {
        boolean present = obj.containsKey(e.getKey());
        if (!present) {
          if (!e.getValue().optional()) {
            throw new TriplateBindingError("missing field: " + label + "." + e.getKey());
          }
          continue;
        }
        validateValue(e.getValue(), obj.get(e.getKey()), label + "." + e.getKey());
      }
    } else if (value instanceof List) {
      throw new TriplateTypeError(label + " must be a scalar, got a list");
    }
  }

  // ---- resolution ----------------------------------------------------------

  private static Resolved resolve(Env env, List<String> path, Pos pos, boolean mustExist) {
    String head = path.get(0);
    TypeExpr type;
    Object value = null;
    boolean present;
    boolean found = false;
    type = null;
    for (int i = env.loop.size() - 1; i >= 0; i--) {
      if (env.loop.get(i).containsKey(head)) {
        Bound b = env.loop.get(i).get(head);
        value = b.value();
        type = b.type();
        found = true;
        break;
      }
    }
    present = found;
    if (!found) {
      type = env.schema.byName().get(head);
      if (type == null) {
        throw new TriplateBindingError("no binding for variable: " + head, pos.line(), pos.column());
      }
      present = env.context.containsKey(head);
      value = present ? env.context.get(head) : null;
    }
    for (int i = 1; i < path.size(); i++) {
      String field = path.get(i);
      if (!(type.base() instanceof RecordType rec)) {
        throw new TriplateTypeError(String.join(".", path.subList(0, i)) + " is not an object",
            pos.line(), pos.column());
      }
      TypeExpr ft = rec.fields().get(field);
      if (ft == null) {
        throw new TriplateBindingError("unknown field: " + String.join(".", path.subList(0, i + 1)),
            pos.line(), pos.column());
      }
      if (present && value instanceof Map<?, ?> map && map.containsKey(field)) {
        value = map.get(field);
      } else {
        present = false;
        value = null;
      }
      type = ft;
    }
    if (mustExist && !present) {
      throw new TriplateBindingError(
          "no value for " + String.join(".", path) + " (an optional value? guard it with {% if %})",
          pos.line(), pos.column());
    }
    return new Resolved(value, type, present);
  }

  private static ScalarType scalarOf(TypeExpr type, List<String> path, Pos pos) {
    if (type.array()) {
      throw new TriplateTypeError(String.join(".", path) + " is a list; loop over it with {% for %}",
          pos.line(), pos.column());
    }
    if (type.base() instanceof RecordType) {
      throw new TriplateTypeError(String.join(".", path) + " is an object; reference a field",
          pos.line(), pos.column());
    }
    return (ScalarType) type.base();
  }

  // ---- rendering -----------------------------------------------------------

  private static String renderNodes(List<Node> nodes, Env env) {
    StringBuilder out = new StringBuilder();
    for (Node node : nodes) {
      if (node instanceof Ast.TextNode t) {
        out.append(t.value());
      } else if (node instanceof ValueNode v) {
        Pos pos = Pos.at(v.line(), v.column());
        Resolved r = resolve(env, v.path(), pos, true);
        out.append(serializeScalar(scalarOf(r.type(), v.path(), pos), r.value(), pos));
      } else if (node instanceof InterpNode i) {
        out.append(renderInterp(env, i));
      } else if (node instanceof IriNode ir) {
        out.append(renderIri(env, ir));
      } else if (node instanceof ForNode f) {
        out.append(renderFor(env, f));
      } else if (node instanceof IfNode iff) {
        out.append(renderIf(env, iff));
      }
    }
    return out.toString();
  }

  private static String serializeScalar(ScalarType t, Object value, Pos pos) {
    return switch (t.kind()) {
      case IRI -> Serializers.serializeIri(value, pos);
      case PNAME -> Serializers.serializePname(value, pos);
      case STRING -> Serializers.serializeString(value, pos);
      case INT -> Serializers.serializeInt(value, pos);
      case DECIMAL -> Serializers.serializeDecimal(value, pos);
      case DOUBLE -> Serializers.serializeDouble(value, pos);
      case BOOL -> Serializers.serializeBoolean(value, pos);
      case DATE -> Serializers.serializeDate(value, pos);
      case DATE_TIME -> Serializers.serializeDateTime(value, pos);
      case TIME -> Serializers.serializeTime(value, pos);
      case LITERAL -> Serializers.serializeTypedLiteral(value, t.datatype(), pos);
      case TERM -> Serializers.serializeTerm(value, pos);
      case RAW -> Serializers.serializeRaw(value, pos);
      case CUSTOM -> {
        TypeRegistry.CustomSerializer fn = TypeRegistry.get(t.name());
        if (fn == null) {
          throw new TriplateTypeError("unknown custom type: " + t.name(), pos.line(), pos.column());
        }
        yield fn.serialize(value, pos.line(), pos.column());
      }
    };
  }

  private record HoleText(boolean raw, String text) {}

  /** Lexical form of a value inside a string/IRI construct. {@code raw} = insert verbatim. */
  private static HoleText holeLexical(Env env, Ast.HolePart part) {
    Pos pos = Pos.at(part.line(), part.column());
    Resolved r = resolve(env, part.path(), pos, true);
    ScalarType t = scalarOf(r.type(), part.path(), pos);
    Object value = r.value();
    return switch (t.kind()) {
      case RAW -> new HoleText(true, Serializers.serializeRaw(value, pos));
      case STRING -> new HoleText(false, Serializers.requireString(value, "string", pos));
      case IRI -> new HoleText(false, Serializers.validateIri(value, pos));
      case PNAME -> new HoleText(false, Serializers.serializePname(value, pos));
      case INT -> new HoleText(false, Serializers.serializeInt(value, pos));
      case DECIMAL -> new HoleText(false, Serializers.serializeDecimal(value, pos));
      case DOUBLE -> new HoleText(false, Serializers.serializeDouble(value, pos));
      case BOOL -> new HoleText(false, Serializers.serializeBoolean(value, pos));
      default -> throw new TriplateTypeError(
          "type " + t.kind() + " cannot be interpolated; use it as a standalone ${ … }",
          part.line(), part.column());
    };
  }

  private static String resolveLang(Env env, LangSpec lang, Pos pos) {
    if (lang instanceof Ast.StaticLang sl) {
      return Serializers.validateLangTag(sl.tag(), pos);
    }
    Ast.PathLang pl = (Ast.PathLang) lang;
    Resolved r = resolve(env, pl.path(), pos, true);
    return Serializers.validateLangTag(r.value(), pos);
  }

  private static String renderInterp(Env env, InterpNode node) {
    StringBuilder content = new StringBuilder();
    for (Part part : node.parts()) {
      if (part instanceof Ast.TextPart tp) {
        content.append(Serializers.escapeString(tp.text()));
        continue;
      }
      HoleText h = holeLexical(env, (Ast.HolePart) part);
      content.append(h.raw() ? h.text() : Serializers.escapeString(h.text()));
    }
    StringBuilder out = new StringBuilder("\"").append(content).append("\"");
    Pos pos = Pos.at(node.line(), node.column());
    if (node.lang() != null) out.append("@").append(resolveLang(env, node.lang(), pos));
    else if (node.datatype() != null) out.append("^^").append(node.datatype());
    return out.toString();
  }

  private static String renderIri(Env env, IriNode node) {
    StringBuilder body = new StringBuilder();
    for (Part part : node.parts()) {
      if (part instanceof Ast.TextPart tp) {
        body.append(tp.text());
        continue;
      }
      HoleText h = holeLexical(env, (Ast.HolePart) part);
      body.append(h.raw() ? h.text() : Serializers.percentEncode(h.text()));
    }
    Pos pos = Pos.at(node.line(), node.column());
    return "<" + Serializers.validateAssembledIri(body.toString(), pos) + ">";
  }

  private static String renderFor(Env env, ForNode node) {
    Pos pos = Pos.at(node.line, node.column);
    Resolved r = resolve(env, node.source, pos, true);
    if (!r.type().array() || !(r.value() instanceof List<?> list)) {
      throw new TriplateTypeError(String.join(".", node.source) + " is not a list", node.line, node.column);
    }
    TypeExpr elemType = r.type().elem();
    List<String> chunks = new ArrayList<>();
    for (Object item : list) {
      List<Map<String, Bound>> loop = new ArrayList<>(env.loop);
      Map<String, Bound> frame = new HashMap<>();
      frame.put(node.item, new Bound(item, elemType));
      loop.add(frame);
      chunks.add(renderNodes(node.body, new Env(env.schema, env.context, loop)));
    }
    if (node.join == null) return String.join("", chunks);
    String separator;
    if (node.joinExact) {
      separator = node.join;
    } else {
      String trimmed = node.join.strip();
      separator = trimmed.isEmpty() ? " " : " " + trimmed + " ";
    }
    if (chunks.isEmpty()) return "";
    String out = chunks.get(0);
    for (int i = 1; i < chunks.size(); i++) {
      out = trimEnd(out) + separator + trimStart(chunks.get(i));
    }
    return out;
  }

  private static String trimEnd(String s) {
    return s.replaceAll("\\s+$", "");
  }

  private static String trimStart(String s) {
    return s.replaceFirst("^\\s+", "");
  }

  private static boolean evalCond(Env env, Cond cond) {
    Pos pos = Pos.at(cond.line(), cond.column());
    Resolved r = resolve(env, cond.path(), pos, false);
    boolean result;
    if (r.type().array()) {
      result = r.present() && r.value() instanceof List<?> list && !list.isEmpty();
    } else if (r.type().base() instanceof ScalarType st && st.kind() == ScalarKind.BOOL) {
      result = r.present() && Boolean.TRUE.equals(r.value());
    } else if (r.type().optional()) {
      result = r.present();
    } else {
      String what = r.type().base() instanceof RecordType
          ? "object"
          : ((ScalarType) r.type().base()).kind().toString().toLowerCase();
      throw new TriplateTypeError(
          "condition on required " + what + " '" + String.join(".", cond.path()) + "' is always true",
          cond.line(), cond.column());
    }
    return cond.negated() ? !result : result;
  }

  private static String renderIf(Env env, IfNode node) {
    for (Branch branch : node.branches) {
      if (evalCond(env, branch.cond)) return renderNodes(branch.body, env);
    }
    if (node.elseBody != null) return renderNodes(node.elseBody, env);
    return "";
  }
}
