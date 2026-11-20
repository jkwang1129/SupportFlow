"""Stable reply argument hashing, not a callback sender or a business tool executor."""
import hashlib
from uuid import UUID

from .types import RunInput


def arguments_hash(value: RunInput, run_id: UUID, snapshot_id: UUID, content: str):
    if not isinstance(content, str) or not content or len(content)>8192 or '\0' in content:
        raise ValueError('Invalid reply text')
    # Match Java Character.isWhitespace rather than Python's wider isspace
    # (which also treats NBSP as whitespace). These code points are protocol v1.
    whitespace = '\t\n\v\f\r\x1c\x1d\x1e\x1f \u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2008\u2009\u200a\u2028\u2029\u205f\u3000'
    if all(character in whitespace for character in content): raise ValueError('Blank reply text')
    try: body=content.encode('utf-8')
    except UnicodeError: raise ValueError('Invalid reply text') from None
    if len(body)>8192: raise ValueError('Reply exceeds UTF-8 byte limit')
    run, snapshot = UUID(str(run_id)), UUID(str(snapshot_id))
    digest=hashlib.sha256(body).hexdigest()
    canonical='\n'.join(('supportflow-session-reply:v1',value.tenant,str(run),str(value.source_event_id),str(snapshot),digest))
    return hashlib.sha256(canonical.encode()).hexdigest()
