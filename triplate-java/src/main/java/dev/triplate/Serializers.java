package dev.triplate;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Per-type validators and serializers — the injection-safe core. Each value is
 * validated and escaped according to its declared RDF type; anything that does
 * not fit throws. Byte-for-byte equivalent to {@code types/serializers.ts}.
 */
final class Serializers {
  private Serializers() {}

  static final String XSD = "http://www.w3.org/2001/XMLSchema#";

  private static final Pattern ABSOLUTE_IRI =
      Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:[^\\u0000-\\u0020<>\"{}|^`\\\\]*$");
  private static final Pattern PNAME =
      Pattern.compile("^(?:[A-Za-z_][A-Za-z0-9_-]*)?:(?:[A-Za-z0-9_](?:[A-Za-z0-9_.-]*[A-Za-z0-9_-])?)?$");
  private static final Pattern LANG_TAG = Pattern.compile("^[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*$");
  private static final Pattern DATE = Pattern.compile("^-?\\d{4,}-\\d{2}-\\d{2}(Z|[+-]\\d{2}:\\d{2})?$");
  private static final Pattern DATE_TIME =
      Pattern.compile("^-?\\d{4,}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})?$");
  private static final Pattern TIME = Pattern.compile("^\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})?$");
  private static final Pattern BLANK_LABEL = Pattern.compile("^[A-Za-z0-9_]+$");

  private static TriplateTypeError fail(String message, Pos pos) {
    return new TriplateTypeError(message, pos.line(), pos.column());
  }

  private static String describe(Object value) {
    if (value instanceof List) return "list";
    if (value == null) return "null";
    if (value instanceof String) return "string";
    if (value instanceof Boolean) return "boolean";
    if (value instanceof Number) return "number";
    return value.getClass().getSimpleName();
  }

  static String requireString(Object value, String what, Pos pos) {
    if (!(value instanceof String s)) {
      throw fail(what + " requires a string value, got " + describe(value), pos);
    }
    return s;
  }

  static String escapeString(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  static String validateIri(Object value, Pos pos) {
    String s = requireString(value, "iri", pos);
    if (!ABSOLUTE_IRI.matcher(s).matches()) {
      throw fail("invalid absolute IRI: " + quote(s), pos);
    }
    return s;
  }

  static String serializeIri(Object value, Pos pos) {
    return "<" + validateIri(value, pos) + ">";
  }

  static String serializePname(Object value, Pos pos) {
    String s = requireString(value, "pname", pos);
    if (!PNAME.matcher(s).matches()) {
      throw fail("invalid prefixed name: " + quote(s), pos);
    }
    return s;
  }

  static String validateLangTag(Object value, Pos pos) {
    String s = requireString(value, "language tag", pos);
    if (!LANG_TAG.matcher(s).matches()) {
      throw fail("invalid language tag: " + quote(s), pos);
    }
    return s;
  }

  static String serializeString(Object value, Pos pos) {
    return "\"" + escapeString(requireString(value, "string", pos)) + "\"";
  }

  static String serializeInt(Object value, Pos pos) {
    if (value instanceof BigInteger bi) return bi.toString();
    if (value instanceof Long || value instanceof Integer
        || value instanceof Short || value instanceof Byte) {
      return value.toString();
    }
    if (value instanceof BigDecimal bd) {
      try {
        return bd.toBigIntegerExact().toString();
      } catch (ArithmeticException ignored) {
        // falls through to the error below
      }
    }
    if (value instanceof Double || value instanceof Float) {
      double d = ((Number) value).doubleValue();
      if (Double.isFinite(d) && d == Math.rint(d) && Math.abs(d) < 9.007199254740992e15) {
        return Long.toString((long) d);
      }
    }
    throw fail("int requires an integral number, got " + describe(value), pos);
  }

  static String serializeDecimal(Object value, Pos pos) {
    if (!(value instanceof Number)) {
      throw fail("decimal requires a finite number, got " + describe(value), pos);
    }
    if (value instanceof Double d && !Double.isFinite(d)) {
      throw fail("decimal requires a finite number", pos);
    }
    if (value instanceof Float f && !Float.isFinite(f)) {
      throw fail("decimal requires a finite number", pos);
    }
    String s = jsReprAbs(value);
    String sign = isNegative(value) ? "-" : "";
    if (s.indexOf('e') >= 0 || s.indexOf('E') >= 0) {
      throw fail("value out of range for decimal: " + s, pos);
    }
    return sign + (s.indexOf('.') >= 0 ? s : s + ".0");
  }

  /**
   * Canonical xsd:double scientific notation built from the shortest round-trip
   * digits: 1.5 -> "1.5E0", 1500000 -> "1.5E6", -0.04 -> "-4.0E-2". The exact
   * same digit manipulation runs in every implementation for byte parity.
   */
  static String serializeDouble(Object value, Pos pos) {
    if (!(value instanceof Number)) {
      throw fail("double requires a number, got " + describe(value), pos);
    }
    double d = ((Number) value).doubleValue();
    if (Double.isNaN(d)) return "\"NaN\"^^<" + XSD + "double>";
    if (d == Double.POSITIVE_INFINITY) return "\"INF\"^^<" + XSD + "double>";
    if (d == Double.NEGATIVE_INFINITY) return "\"-INF\"^^<" + XSD + "double>";
    if (d == 0.0) return "0.0E0";

    String sign = isNegative(value) ? "-" : "";
    String repr = jsReprAbs(value);
    String mantissa = repr;
    int exp = 0;
    int eIndex = indexOfExponent(repr);
    if (eIndex >= 0) {
      mantissa = repr.substring(0, eIndex);
      exp = Integer.parseInt(repr.substring(eIndex + 1));
    }
    String digits;
    int pointPos;
    int dot = mantissa.indexOf('.');
    if (dot >= 0) {
      digits = mantissa.substring(0, dot) + mantissa.substring(dot + 1);
      pointPos = dot;
    } else {
      digits = mantissa;
      pointPos = mantissa.length();
    }
    String noLead = stripLeadingZeros(digits);
    int leading = digits.length() - noLead.length();
    digits = stripTrailingZeros(noLead);
    int exponent = pointPos - leading - 1 + exp;
    String fraction = digits.length() > 1 ? digits.substring(1) : "0";
    return sign + digits.charAt(0) + "." + fraction + "E" + exponent;
  }

  static String serializeBoolean(Object value, Pos pos) {
    if (!(value instanceof Boolean b)) {
      throw fail("bool requires a boolean, got " + describe(value), pos);
    }
    return b ? "true" : "false";
  }

  static String serializeDate(Object value, Pos pos) {
    return temporal(value, pos, "date", DATE, v -> {
      if (v instanceof LocalDate ld) return ld.toString();
      if (v instanceof LocalDateTime ldt) return ldt.toLocalDate().toString();
      if (v instanceof OffsetDateTime odt) return odt.toLocalDate().toString();
      if (v instanceof Instant in) return in.atOffset(java.time.ZoneOffset.UTC).toLocalDate().toString();
      return null;
    });
  }

  static String serializeDateTime(Object value, Pos pos) {
    return temporal(value, pos, "dateTime", DATE_TIME, v -> {
      if (v instanceof Instant in) return DateTimeFormatter.ISO_INSTANT.format(in);
      if (v instanceof OffsetDateTime odt) return odt.toString();
      if (v instanceof LocalDateTime ldt) return ldt.toString();
      return null;
    });
  }

  static String serializeTime(Object value, Pos pos) {
    return temporal(value, pos, "time", TIME, v -> {
      if (v instanceof LocalTime lt) return lt.toString();
      if (v instanceof LocalDateTime ldt) return ldt.toLocalTime().toString();
      if (v instanceof OffsetDateTime odt) return odt.toLocalTime().toString();
      return null;
    });
  }

  private interface FromTemporal {
    String apply(Object value);
  }

  private static String temporal(Object value, Pos pos, String name, Pattern pattern, FromTemporal fromTemporal) {
    String lexical;
    if (value instanceof String s) {
      if (!pattern.matcher(s).matches()) {
        throw fail("invalid " + name + " value: " + quote(s), pos);
      }
      lexical = s;
    } else {
      lexical = fromTemporal.apply(value);
      if (lexical == null) {
        throw fail(name + " requires a date/time value or ISO string, got " + describe(value), pos);
      }
    }
    return "\"" + lexical + "\"^^<" + XSD + name + ">";
  }

  static String serializeTypedLiteral(Object value, String datatype, Pos pos) {
    String s = requireString(value, "literal(" + datatype + ")", pos);
    return "\"" + escapeString(s) + "\"^^" + datatype;
  }

  /** RDF term: NamedNode, BlankNode or Literal. */
  static String serializeTerm(Object value, Pos pos) {
    if (!(value instanceof Term t)) {
      throw fail("term requires an RDF term object, got " + describe(value), pos);
    }
    if (t.termType() == null || t.value() == null) {
      throw fail("term requires an RDF term object", pos);
    }
    switch (t.termType()) {
      case "NamedNode":
        return serializeIri(t.value(), pos);
      case "BlankNode":
        if (!BLANK_LABEL.matcher(t.value()).matches()) {
          throw fail("invalid blank node label: " + quote(t.value()), pos);
        }
        return "_:" + t.value();
      case "Literal": {
        String quoted = "\"" + escapeString(t.value()) + "\"";
        if (t.language() != null) return quoted + "@" + validateLangTag(t.language(), pos);
        String dt = t.datatypeIri();
        if (dt != null && !dt.equals(XSD + "string")) return quoted + "^^" + serializeIri(dt, pos);
        return quoted;
      }
      default:
        throw fail("unsupported term type: " + t.termType(), pos);
    }
  }

  static String serializeRaw(Object value, Pos pos) {
    return requireString(value, "raw", pos);
  }

  /**
   * Percent-encode a string to the IRI unreserved set {@code A-Za-z0-9-._~};
   * every other character becomes its UTF-8 bytes as uppercase {@code %XX}.
   * Identical output to TypeScript's encodeURIComponent variant and Python's
   * {@code urllib.parse.quote(s, safe='')}.
   */
  static String percentEncode(String s) {
    StringBuilder out = new StringBuilder();
    for (byte raw : s.getBytes(StandardCharsets.UTF_8)) {
      int c = raw & 0xFF;
      if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
          || c == '-' || c == '_' || c == '.' || c == '~') {
        out.append((char) c);
      } else {
        out.append('%').append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)))
            .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
      }
    }
    return out.toString();
  }

  static String validateAssembledIri(String s, Pos pos) {
    if (!ABSOLUTE_IRI.matcher(s).matches()) {
      throw fail("IRI template did not produce a valid absolute IRI: " + quote(s), pos);
    }
    return s;
  }

  // ---- number helpers ------------------------------------------------------

  private static boolean isNegative(Object value) {
    if (value instanceof BigInteger bi) return bi.signum() < 0;
    if (value instanceof BigDecimal bd) return bd.signum() < 0;
    return ((Number) value).doubleValue() < 0;
  }

  /** The JavaScript {@code String(Math.abs(value))} of a number, used by the double/decimal algorithms. */
  private static String jsReprAbs(Object value) {
    if (value instanceof BigInteger bi) return bi.abs().toString();
    if (value instanceof BigDecimal bd) return bd.abs().stripTrailingZeros().toPlainString();
    if (value instanceof Long || value instanceof Integer
        || value instanceof Short || value instanceof Byte) {
      return Long.toString(Math.abs(((Number) value).longValue()));
    }
    return Double.toString(Math.abs(((Number) value).doubleValue()));
  }

  private static int indexOfExponent(String s) {
    int e = s.indexOf('e');
    return e >= 0 ? e : s.indexOf('E');
  }

  private static String stripLeadingZeros(String s) {
    int i = 0;
    while (i < s.length() && s.charAt(i) == '0') i++;
    return s.substring(i);
  }

  private static String stripTrailingZeros(String s) {
    int i = s.length();
    while (i > 0 && s.charAt(i - 1) == '0') i--;
    return s.substring(0, i);
  }

  /** A JSON-ish double-quoted rendering of a string, for error messages only. */
  private static String quote(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
