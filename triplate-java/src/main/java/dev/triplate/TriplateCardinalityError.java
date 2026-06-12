package dev.triplate;

/** Raised at render time when an array's length is outside its declared min/max. */
public class TriplateCardinalityError extends TriplateError {
  public TriplateCardinalityError(String message) {
    super(message);
  }

  public TriplateCardinalityError(String message, Integer line, Integer column) {
    super(message, line, column);
  }
}
