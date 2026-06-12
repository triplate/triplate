class TriplateError(Exception):
    """Base class for all Triplate errors."""

    def __init__(self, message, line=None, column=None):
        if line is not None:
            super().__init__(f"{message} (line {line}, column {column})")
        else:
            super().__init__(message)
        self.line = line
        self.column = column


class TriplateSyntaxError(TriplateError):
    pass


class TriplateBindingError(TriplateError):
    pass


class TriplateTypeError(TriplateError):
    pass


class TriplateCardinalityError(TriplateError):
    pass
