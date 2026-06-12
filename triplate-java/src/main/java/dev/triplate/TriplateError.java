package dev.triplate;

/** Base class for every error raised by Triplate. Carries an optional source location. */
public class TriplateError extends RuntimeException {
  private final Integer line;
  private final Integer column;

  public TriplateError(String message) {
    this(message, null, null);
  }

  public TriplateError(String message, Integer line, Integer column) {
    super(line != null ? message + " (line " + line + ", column " + column + ")" : message);
    this.line = line;
    this.column = column;
  }

  /** 1-based source line, or {@code null} when not available. */
  public Integer getLine() {
    return line;
  }

  /** 1-based source column, or {@code null} when not available. */
  public Integer getColumn() {
    return column;
  }
}
