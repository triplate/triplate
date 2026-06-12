export class TriplateError extends Error {
  readonly line?: number;
  readonly column?: number;

  constructor(message: string, line?: number, column?: number) {
    super(line != null ? `${message} (line ${line}, column ${column})` : message);
    this.name = new.target.name;
    this.line = line;
    this.column = column;
  }
}

export class TriplateSyntaxError extends TriplateError {}
export class TriplateBindingError extends TriplateError {}
export class TriplateTypeError extends TriplateError {}
export class TriplateCardinalityError extends TriplateError {}
