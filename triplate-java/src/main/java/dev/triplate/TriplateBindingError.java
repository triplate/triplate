package dev.triplate;

/** Raised at render time when a value is missing, unknown, or otherwise unbound. */
public class TriplateBindingError extends TriplateError {
  public TriplateBindingError(String message) {
    super(message);
  }

  public TriplateBindingError(String message, Integer line, Integer column) {
    super(message, line, column);
  }
}
