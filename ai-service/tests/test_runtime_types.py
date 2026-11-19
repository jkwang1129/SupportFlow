from dataclasses import replace
from uuid import uuid4

import pytest

from ai.runtime.types import RunInput, Limits, reference


@pytest.mark.parametrize('tenant', ['', 'PLATFORM', 'platform', 'bad space', 'x'*17, None])
def test_runtime_rejects_untrusted_tenant_forms(tenant):
    with pytest.raises(ValueError):
        RunInput(tenant,uuid4(),'S1',1,1,'visitor:42','trace-1')


@pytest.mark.parametrize('limits', [dict(max_steps=0),dict(max_model_calls=-1),dict(max_tool_calls=True),dict(max_retries=11),dict(wall_seconds=0)])
def test_limits_are_bounded_integers(limits):
    with pytest.raises(ValueError):
        Limits(**limits)


def test_input_fingerprint_includes_subject_event_sequence_and_immutable_limits():
    value = RunInput('TENANT_A',uuid4(),'S1',1,1,'visitor:42','trace-1')
    assert value.hash(Limits()) == replace(value).hash(Limits())
    for changed in (replace(value,principal_ref='visitor:99'),replace(value,event_seq=2),replace(value,message_id=3)):
        assert value.hash(Limits()) != changed.hash(Limits())
    assert value.hash(Limits()) != value.hash(Limits(max_retries=1))


@pytest.mark.parametrize('payload', [{'Authorization':'Bearer private'}, {'prompt':'private'}, {'token':'private'}, {'resource_ref':'has whitespace'}])
def test_step_references_do_not_accept_raw_credentials_or_prompt_fields(payload):
    with pytest.raises(ValueError):
        reference(payload)
