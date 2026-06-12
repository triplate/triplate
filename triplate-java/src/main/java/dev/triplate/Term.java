package dev.triplate;

/**
 * A minimal, dependency-free RDF term accepted by the {@code term} type — the
 * Java analogue of RDF/JS terms (TypeScript) and rdflib terms (Python).
 *
 * <p>Implement this over your RDF library of choice (Apache Jena, Eclipse
 * RDF4J, …) to feed existing terms straight into a template. {@link #termType()}
 * must be one of {@code "NamedNode"}, {@code "BlankNode"} or {@code "Literal"}.
 */
public interface Term {
  /** {@code "NamedNode"}, {@code "BlankNode"} or {@code "Literal"}. */
  String termType();

  /** The IRI, blank-node label, or lexical form, depending on {@link #termType()}. */
  String value();

  /** For a {@code Literal}, the language tag, or {@code null}. */
  default String language() {
    return null;
  }

  /** For a {@code Literal}, the datatype IRI, or {@code null}. */
  default String datatypeIri() {
    return null;
  }
}
