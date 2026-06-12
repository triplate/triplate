package dev.triplate;

/** Raised at render time when a value does not match its declared type. */
public class TriplateTypeError extends TriplateError {
  public TriplateTypeError(String message) {
    super(message);
  }

  public TriplateTypeError(String message, Integer line, Integer column) {
    super(message, line, column);
  }
}
