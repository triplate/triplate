package dev.triplate;

import dev.triplate.Ast.CompiledTemplateData;
import dev.triplate.Ast.ExampleSet;
import dev.triplate.Ast.Schema;

import java.util.List;
import java.util.Map;

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
}
