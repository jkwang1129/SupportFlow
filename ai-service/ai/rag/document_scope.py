"""Document selection is narrowing only; it never grants tenant/document access."""


def document_scope(value):
    """None keeps the caller's tenant scope; [] selects no documents."""
    if value is None:
        return None
    if type(value) is not list or len(value) > 200 or any(type(item) is not int or not 1 <= item <= 9223372036854775807 for item in value):
        raise ValueError('Document scope requires at most 200 positive int64 identifiers')
    return list(dict.fromkeys(value))


def published_policy(value):
    if type(value) is not bool:
        raise ValueError('Host visibility policy must be boolean')
    return value
