package dev.triplate;

/** An optional source location, threaded into serializers for error reporting. */
record Pos(Integer line, Integer column) {
  static final Pos NONE = new Pos(null, null);

  static Pos at(int line, int column) {
    return new Pos(line, column);
  }
}
