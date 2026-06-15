package dev.triplate;

import dev.triplate.Ast.CompiledTemplateData;
import dev.triplate.Ast.ExampleSet;
import dev.triplate.Ast.ExampleValue;
import dev.triplate.Ast.ParamDecl;
import dev.triplate.Ast.RecordType;
import dev.triplate.Ast.ScalarKind;
import dev.triplate.Ast.ScalarType;
import dev.triplate.Ast.Schema;
import dev.triplate.Ast.TypeExpr;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A template parsed once and rendered many times. Obtain one from
 * {@link Triplate#compile(String)}.
 */
public final class CompiledTemplate {
  private final CompiledTemplateData data;
  private final String source;

  CompiledTemplate(CompiledTemplateData data, String source) {
    this.data = data;
    this.source = source;
  }

  /** The declared parameter schema (from the {@code ---} frontmatter header). */
  public Schema schema() {
    return data.schema();
  }

  /** The named example sets (from {@code example} blocks in the frontmatter). */
  public List<ExampleSet> examples() {
    return data.examples();
  }

  /** Render with no context (valid only when every parameter is optional). */
  public String render() {
    return render(Map.of());
  }

  /** Render with a caller-supplied context. */
  public String render(Map<String, Object> context) {
    return Renderer.render(data, context);
  }

  /** Render using a named example set (for development/preview). */
  public String previewExample(String id) {
    ExampleSet set = data.examples().stream()
        .filter(e -> e.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new TriplateError("no example set with id: " + id));
    Map<String, Object> context =
        Examples.exampleSetToContext(set, data.schema(), Examples.extractPrefixes(source));
    return Renderer.render(data, context);
  }

  /**
   * Builds a render context from raw string inputs (e.g. CLI args or editor
   * prompts), coercing each value to its declared scalar type and validating the
   * result against the schema.
   *
   * <p>Per declared parameter: an absent or blank input is omitted (so optional
   * params stay absent); an array param is split on commas (each item trimmed,
   * blanks dropped) and coerced element-wise; a record param cannot be expressed
   * as a string and is skipped — supply records via an example block instead.
   * Throws {@link TriplateTypeError} for uncoercible values and the usual
   * binding / cardinality errors for structural problems.
   */
  public Map<String, Object> contextFromStrings(Map<String, String> inputs) {
    Map<String, Object> context = new LinkedHashMap<>();
    for (ParamDecl param : data.schema().params()) {
      TypeExpr type = param.type();
      if (type.base() instanceof RecordType) continue;
      String raw = inputs.get(param.name());
      if (raw == null || raw.strip().isEmpty()) continue;
      ScalarKind kind = ((ScalarType) type.base()).kind();
      if (type.array()) {
        List<Object> items = new ArrayList<>();
        for (String part : raw.split(",")) {
          String item = part.strip();
          if (!item.isEmpty()) items.add(coerceScalar(kind, item));
        }
        context.put(param.name(), items);
      } else {
        context.put(param.name(), coerceScalar(kind, raw));
      }
    }
    Renderer.validateContext(data.schema(), context);
    return context;
  }

  /**
   * The namespace prefixes referenced in the frontmatter — in {@code example}
   * binding values ({@code prefix:local}), in literal datatypes on those values
   * ({@code "x"^^p:t}), and in {@code literal(p:t)} parameter types.
   *
   * <p>These usages are invisible to a token stream over the body (tools blank
   * the frontmatter before tokenizing), so a linter can use this to avoid
   * flagging a body {@code PREFIX} declaration as unused. Full {@code <iri>}
   * values contribute nothing; the empty string denotes the default prefix.
   */
  public Set<String> frontmatterPrefixes() {
    Set<String> out = new HashSet<>();
    for (ExampleSet set : data.examples()) {
      for (ExampleValue ev : set.bindings().values()) collectValuePrefixes(ev, out);
    }
    for (ParamDecl param : data.schema().params()) collectTypePrefixes(param.type(), out);
    return out;
  }

  /** Adds the prefix of a datatype reference ({@code prefix:local}); {@code <iri>} forms add nothing. */
  private static void addDatatypePrefix(String dt, Set<String> out) {
    if (dt.startsWith("<")) return;
    int i = dt.indexOf(':');
    if (i >= 0) out.add(dt.substring(0, i));
  }

  private static void collectValuePrefixes(ExampleValue ev, Set<String> out) {
    if (ev instanceof Ast.PnameVal p) {
      out.add(p.prefix());
    } else if (ev instanceof Ast.StringVal s) {
      if (s.datatype() != null) addDatatypePrefix(s.datatype(), out);
    } else if (ev instanceof Ast.ListVal l) {
      for (ExampleValue it : l.items()) collectValuePrefixes(it, out);
    } else if (ev instanceof Ast.RecordVal r) {
      for (ExampleValue f : r.fields().values()) collectValuePrefixes(f, out);
    }
    // IriVal, NumberVal, BoolVal → no prefix
  }

  private static void collectTypePrefixes(TypeExpr type, Set<String> out) {
    if (type.base() instanceof RecordType rec) {
      for (TypeExpr ft : rec.fields().values()) collectTypePrefixes(ft, out);
    } else if (type.base() instanceof ScalarType st && st.kind() == ScalarKind.LITERAL) {
      addDatatypePrefix(st.datatype(), out);
    }
  }

  /** Coerces a raw string to a typed value for the given scalar kind. */
  private static Object coerceScalar(ScalarKind kind, String raw) {
    switch (kind) {
      case INT:
        if (!raw.matches("[+-]?\\d+")) throw new TriplateTypeError("invalid int: \"" + raw + "\"");
        try {
          return Long.parseLong(raw);
        } catch (NumberFormatException e) {
          return new BigInteger(raw);
        }
      case DECIMAL:
      case DOUBLE:
        double n;
        try {
          n = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
          throw new TriplateTypeError("invalid " + kind.toString().toLowerCase() + ": \"" + raw + "\"");
        }
        if (!Double.isFinite(n)) {
          throw new TriplateTypeError("invalid " + kind.toString().toLowerCase() + ": \"" + raw + "\"");
        }
        return n;
      case BOOL:
        if (raw.equals("true")) return Boolean.TRUE;
        if (raw.equals("false")) return Boolean.FALSE;
        throw new TriplateTypeError("invalid bool: \"" + raw + "\" (expected \"true\" or \"false\")");
      default:
        // IRI, PNAME, STRING, LITERAL, TERM, RAW, DATE, DATE_TIME, TIME, CUSTOM → string
        return raw;
    }
  }
}
