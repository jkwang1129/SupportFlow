import hashlib
import json
import pytest
from ai.runtime.payloads import encode,decode


def test_deterministic_utf8_payload_roundtrip_without_hidden_normalization():
    value={'z':['原始\n"引用"',1,True,None,0.25],'a':{'action':'reply'}}
    raw,digest=encode('model-output-v1',value)
    assert raw==json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'))
    assert digest==hashlib.sha256(raw.encode()).hexdigest() and decode('model-output-v1',raw,digest)==value
    assert encode('model-output-v1',{'x':'é'})!=encode('model-output-v1',{'x':'e\u0301'})


@pytest.mark.parametrize('value',[[],None,'raw text',{'x':float('nan')},{'x':float('inf')},{'x':2**63},
    {'x':object()},{1:'coercion'},{'x':{1:'nested'}},{'x':'a\0b'},{'x':'\ud800'},{'\0':'value'},
    {'x':'中'*22000},{'x':['x']*4096}])
def test_non_json_invalid_numbers_unicode_and_resource_abuse_are_rejected(value):
    with pytest.raises(ValueError):encode('model-output-v1',value)


def test_exact_byte_depth_and_node_limits_and_cycles():
    value={'x':'a'*(65536-8)}
    assert len(encode('model-output-v1',value)[0].encode())==65536
    with pytest.raises(ValueError):encode('model-output-v1',{'x':'a'*(65536-7)})
    value={};nested=value
    for _ in range(33):nested['x']={};nested=nested['x']
    with pytest.raises(ValueError):encode('model-output-v1',value)
    cycle={};cycle['x']=cycle
    with pytest.raises(ValueError):encode('model-output-v1',cycle)
    assert encode('working-state-v1',{'x':list(range(4094))})


def test_read_rejects_corrupt_hash_duplicate_fields_and_excessive_structure():
    raw,digest=encode('tool-output-v1',{'hits':[]})
    with pytest.raises(ValueError):decode('tool-output-v1',raw,'0'*64)
    raw='{"hits":[],"hits":[1]}'
    with pytest.raises(ValueError):decode('tool-output-v1',raw,hashlib.sha256(raw.encode()).hexdigest())
    with pytest.raises(ValueError):encode('unregistered-v1',{})
