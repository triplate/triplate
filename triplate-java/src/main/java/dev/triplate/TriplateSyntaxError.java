package dev.triplate;

/** Raised at compile time for a malformed template. */
public class TriplateSyntaxError extends TriplateError {
  public TriplateSyntaxError(String message) {
    super(message);
  }

  public TriplateSyntaxError(String message, Integer line, Integer column) {
    super(message, line, column);
  }
}
