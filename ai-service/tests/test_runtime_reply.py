"""Cross-language reply fingerprints bind exact content and resource references."""
from dataclasses import replace
from uuid import UUID, uuid4

import pytest

from ai.runtime.reply import arguments_hash
from ai.runtime.types import RunInput

RUN = UUID('00000000-0000-0000-0000-000000000001')
EVENT = UUID('00000000-0000-0000-0000-000000000002')
SNAPSHOT = UUID('00000000-0000-0000-0000-000000000003')
TEXT = '答复\n"exact" '


def source():
    return RunInput('TENANT_A', EVENT, 'S1', 1, 42, 'visitor-session:7', 'reply-test')


def test_fixed_java_python_protocol_fingerprint():
    assert arguments_hash(source(), RUN, SNAPSHOT, TEXT) == '78e607df16ab9a644dff1e4cec93312f716695ef270c89a786f768589afae522'


@pytest.mark.parametrize('content', ['', ' \r\n\t', '\u3000', 'a\0b', 'x'*8193, '中'*2731, '\ud800', None, 1])
def test_invalid_blank_nul_utf8_and_excessive_text_is_rejected(content):
    with pytest.raises(ValueError): arguments_hash(source(), RUN, SNAPSHOT, content)


@pytest.mark.parametrize('field', ['tenant', 'run', 'event', 'snapshot', 'content'])
def test_fingerprint_cannot_be_reused_for_another_subject_or_content(field):
    value, run, snapshot, text = source(), RUN, SNAPSHOT, TEXT
    if field == 'tenant': value = replace(value, tenant='TENANT_B')
    if field == 'event': value = replace(value, source_event_id=uuid4())
    if field == 'run': run = uuid4()
    if field == 'snapshot': snapshot = uuid4()
    if field == 'content': text = TEXT.rstrip()
    assert arguments_hash(value,run,snapshot,text) != arguments_hash(source(),RUN,SNAPSHOT,TEXT)


def test_exact_utf8_byte_boundary_and_no_unicode_normalization():
    assert len(arguments_hash(source(),RUN,SNAPSHOT,'x'*8192)) == 64
    assert len(arguments_hash(source(),RUN,SNAPSHOT,'\u00a0')) == 64  # Java protocol v1 does not classify NBSP as blank.
    assert arguments_hash(source(),RUN,SNAPSHOT,'é') != arguments_hash(source(),RUN,SNAPSHOT,'e\u0301')
