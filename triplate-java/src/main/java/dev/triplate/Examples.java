package dev.triplate;

import dev.triplate.Ast.ExampleSet;
import dev.triplate.Ast.ExampleValue;
import dev.triplate.Ast.RecordType;
import dev.triplate.Ast.ScalarKind;
import dev.triplate.Ast.ScalarType;
import dev.triplate.Ast.Schema;
import dev.triplate.Ast.TypeExpr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves example sets into render contexts (development/preview). Port of {@code examples.ts}. */
final class Examples {
  private Examples() {}

  private static final Pattern PREFIX_RE = Pattern.compile(
      "(?:PREFIX|@prefix)\\s+([A-Za-z_][\\w.-]*)?\\s*:\\s*<([^>]*)>", Pattern.CASE_INSENSITIVE);

  /** Extract prefix → namespace IRI from a template's PREFIX / @prefix declarations. */
  static Map<String, String> extractPrefixes(String template) {
    Map<String, String> out = new LinkedHashMap<>();
    Matcher m = PREFIX_RE.matcher(template);
    while (m.find()) {
      out.put(m.group(1) == null ? "" : m.group(1), m.group(2));
    }
    return out;
  }

  /** Convert an example set into a render context, resolving prefixed names. */
  static Map<String, Object> exampleSetToContext(ExampleSet set, Schema schema, Map<String, String> prefixes) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    for (Map.Entry<String, ExampleValue> e : set.bindings().entrySet()) {
      TypeExpr type = schema.byName().get(e.getKey());
      if (type == null) {
        throw new TriplateError("example \"" + set.id() + "\" binds unknown parameter: " + e.getKey());
      }
      ctx.put(e.getKey(), convert(e.getValue(), type, prefixes, set.id()));
    }
    return ctx;
  }

  private static Object convert(ExampleValue ev, TypeExpr type, Map<String, String> prefixes, String id) {
    if (type.array()) {
      if (!(ev instanceof Ast.ListVal list)) {
        throw new TriplateError("example \"" + id + "\": expected a list");
      }
      TypeExpr elem = type.elem();
      List<Object> out = new ArrayList<>();
      for (ExampleValue it : list.items()) out.add(convert(it, elem, prefixes, id));
      return out;
    }
    if (type.base() instanceof RecordType rec) {
      if (!(ev instanceof Ast.RecordVal record)) {
        throw new TriplateError("example \"" + id + "\": expected a record");
      }
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<String, TypeExpr> f : rec.fields().entrySet()) {
        if (record.fields().containsKey(f.getKey())) {
          out.put(f.getKey(), convert(record.fields().get(f.getKey()), f.getValue(), prefixes, id));
        }
      }
      return out;
    }
    ScalarKind scalar = type.base() instanceof ScalarType st ? st.kind() : null;
    if (ev instanceof Ast.IriVal iri) {
      return iri.value();
    }
    if (ev instanceof Ast.PnameVal pn) {
      if (scalar == ScalarKind.PNAME) return pn.prefix() + ":" + pn.local();
      String ns = prefixes.get(pn.prefix());
      if (ns == null) {
        throw new TriplateError("example \"" + id + "\": unknown prefix '" + pn.prefix() + ":'");
      }
      return ns + pn.local();
    }
    if (ev instanceof Ast.StringVal sv) {
      return sv.value();
    }
    if (ev instanceof Ast.NumberVal nv) {
      return nv.value();
    }
    if (ev instanceof Ast.BoolVal bv) {
      return bv.value();
    }
    throw new TriplateError("example \"" + id + "\": value does not match declared type");
  }
}
