"""Registry for custom datatypes (extensibility hook).

A registered type is usable as %name:<typename>; the serializer must validate
the value and return the exact SPARQL text to emit (it is fully responsible
for safety). The serializer is called as serializer(value, pos) where pos has
.line and .column attributes.
"""

_custom_types = {}


def register_type(name, serializer):
    _custom_types[name.lower()] = serializer


def get_custom_type(name):
    return _custom_types.get(name.lower())


def has_custom_type(name):
    return name.lower() in _custom_types
