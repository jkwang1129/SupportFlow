"""Reply/receipt protocol only. Ambiguous network failure is never proof of no side effect."""
from dataclasses import dataclass
import json
import re
from uuid import UUID

from .source import _CustomerTransport
from .reply import arguments_hash
from .types import RunInput, Lease, identifier
from .worker import PermanentFailure


@dataclass(frozen=True)
class ReplyReceipt:
    run_id: UUID
    event_id: UUID
    snapshot_id: UUID
    arguments_sha256: str
    message_id: int
    message_no: str
    sequence: int


def receipt_reference(receipt,source,run_id,snapshot,digest):
    """Validate a typed receipt again before it can become a durable outcome."""
    if (not isinstance(receipt,ReplyReceipt) or receipt.run_id!=run_id or receipt.event_id!=source.source_event_id
            or receipt.snapshot_id!=snapshot or receipt.arguments_sha256!=digest
            or type(receipt.message_id) is not int or not 1<=receipt.message_id<=9223372036854775807
            or type(receipt.sequence) is not int or not 1<=receipt.sequence<=9223372036854775807):
        raise PermanentFailure('REPLY_RECEIPT_MISMATCH')
    try:identifier(receipt.message_no)
    except ValueError:raise PermanentFailure('REPLY_RECEIPT_MISMATCH') from None
    return dict(operation='session.reply',version='reply-v1',outcome='reply-committed',business_ref='message:'+str(receipt.message_id),
                resource_ref=receipt.message_no,input_ref=str(snapshot),arguments_sha256=digest)


class ResultClient(_CustomerTransport):
    async def reply(self, source: RunInput, lease: Lease, snapshot_id: UUID, content: str):
        if lease.tenant!=source.tenant or type(lease.fence) is not int or not 1<=lease.fence<=9223372036854775807:
            raise ValueError('Invalid reply lease')
        identifier(lease.owner)
        run,snapshot=UUID(str(lease.run_id)),UUID(str(snapshot_id))
        digest=arguments_hash(source,run,snapshot,content)
        body=json.dumps(dict(snapshot_id=str(snapshot),owner=lease.owner,fence=lease.fence,
                             arguments_sha256=digest,content=content,version='reply-v1'),ensure_ascii=False,separators=(',',':')).encode()
        status,raw=await self._exchange('POST',source,run,'reply',body,prefix='REPLY',limit=8192,accepted=(200,404,409,410,422))
        return self._decode(status,raw,source,run,expected_snapshot_id=snapshot,expected_hash=digest)

    async def receipt(self, source: RunInput, run_id: UUID, *, expected_snapshot_id=None, expected_hash=None):
        run=UUID(str(run_id))
        status,raw=await self._exchange('GET',source,run,'reply',b'',prefix='REPLY',limit=8192,accepted=(200,404))
        return self._decode(status,raw,source,run,expected_snapshot_id=expected_snapshot_id,expected_hash=expected_hash,read=True)

    @staticmethod
    def _decode(status,raw,source,run,*,expected_snapshot_id=None,expected_hash=None,read=False):
        def unique(pairs):
            result={}
            for key,value in pairs:
                if key in result: raise ValueError('Duplicate result field')
                result[key]=value
            return result
        try:
            root=json.loads(raw,object_pairs_hook=unique)
            if not isinstance(root,dict) or type(root.get('code')) is not int: raise ValueError()
            code=root['code']
            if read and status==404 and code==40403 and root.get('data') is None: return None
            if code!=0:
                failures={404: 'REPLY_AUTHORITY_DENIED',409:'REPLY_AUTHORITY_DENIED',40402:'REPLY_RESOURCE_REVOKED',
                          40403:'REPLY_RESOURCE_REVOKED',40902:'REPLY_SOURCE_CHANGED',40903:'REPLY_CONFLICT',41002:'REPLY_SOURCE_EXPIRED'}
                raise PermanentFailure(failures.get(code,'REPLY_REJECTED'))
            if status!=200: raise ValueError()
            value=root['data']
            fields={'tenant_code','source_event_id','run_id','snapshot_id','schema_version','operation','arguments_sha256','message_id','message_no','seq'}
            if not isinstance(value,dict) or set(value)!=fields or value['tenant_code']!=source.tenant or value['source_event_id']!=str(source.source_event_id) or value['run_id']!=str(run): raise ValueError()
            if value['schema_version']!='reply-v1' or value['operation']!='session.reply': raise ValueError()
            snapshot=UUID(value['snapshot_id'])
            if str(snapshot)!=value['snapshot_id'] or not isinstance(value['arguments_sha256'],str) or not re.fullmatch('[0-9a-f]{64}',value['arguments_sha256']): raise ValueError()
            if not isinstance(value['message_id'],str) or not re.fullmatch('[1-9][0-9]{0,18}',value['message_id']) or int(value['message_id'])>9223372036854775807: raise ValueError()
            if not isinstance(value['seq'],str) or not re.fullmatch('[1-9][0-9]{0,18}',value['seq']) or int(value['seq'])>9223372036854775807: raise ValueError()
            identifier(value['message_no'])
            if (expected_snapshot_id is not None and str(snapshot)!=str(expected_snapshot_id)) or (expected_hash is not None and value['arguments_sha256']!=expected_hash):
                raise PermanentFailure('REPLY_RECEIPT_MISMATCH')
            return ReplyReceipt(run,source.source_event_id,snapshot,value['arguments_sha256'],int(value['message_id']),value['message_no'],int(value['seq']))
        except (ValueError,TypeError,KeyError,UnicodeError,RecursionError,AttributeError):
            raise PermanentFailure('REPLY_PAYLOAD_INVALID') from None
