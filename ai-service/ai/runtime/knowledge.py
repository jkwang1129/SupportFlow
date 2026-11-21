"""Fixed knowledge API and strict result binding; budget reservation belongs to host."""
from dataclasses import dataclass,field
import hashlib
import hmac
import json
import re
from uuid import UUID

from .embeddings import RuntimeEmbeddingClient
from .source import _CustomerTransport
from .types import RunInput
from .worker import PermanentFailure

POLICY='visitor-published-v1'
MODEL_PATTERN=r'[A-Za-z0-9][A-Za-z0-9_.-]{0,31}:[A-Za-z0-9][A-Za-z0-9_.:-]{0,94}'
SHA_PATTERN=r'[0-9a-f]{64}'


def _sha(value):return hashlib.sha256(value.encode('utf-8')).hexdigest()


def knowledge_digest(value):
    """Projection integrity only; neither a signature nor a corpus version."""
    parts=['supportflow-knowledge-search:v1',value['policy_version'],value['tenant_code'],value['source_event_id'],
        value['run_id'],value['snapshot_id'],value['source_hash'],value['request_sha256'],value['query_sha256'],
        value['embedding_model'],str(len(value['hits']))]
    for hit in value['hits']:
        parts.extend((str(hit['doc_id']),str(hit['chunk_id']),_sha(hit['title']),_sha(hit['snippet']),str(hit['score_ppm']),hit['embedding_model']))
    return _sha('\n'.join(parts))


@dataclass(frozen=True)
class KnowledgeHit:
    doc_id: str
    chunk_id: str
    title: str=field(repr=False)
    snippet: str=field(repr=False)
    score_ppm: int
    embedding_model: str

    def result(self):return dict(doc_id=self.doc_id,chunk_id=self.chunk_id,title=self.title,snippet=self.snippet,
                                 score_ppm=self.score_ppm,embedding_model=self.embedding_model)


@dataclass(frozen=True)
class KnowledgeSearch:
    event_id: UUID
    run_id: UUID
    snapshot_id: UUID
    source_hash: str
    request_sha256: str
    query_sha256: str
    embedding_model: str
    hits: tuple[KnowledgeHit,...]=field(repr=False)
    resource_version: str

    def result(self):
        return dict(schema_version='knowledge-search-v1',policy_version=POLICY,source_event_id=str(self.event_id),
            run_id=str(self.run_id),snapshot_id=str(self.snapshot_id),source_hash=self.source_hash,request_sha256=self.request_sha256,
            query_sha256=self.query_sha256,embedding_model=self.embedding_model,hits=[hit.result() for hit in self.hits],
            resource_version=self.resource_version)


class KnowledgeClient(_CustomerTransport):
    async def search(self,source: RunInput,run_id: UUID,*,snapshot_id,source_hash,query_sha256,embedding_model,vector,top_k=5,doc_ids=None):
        try:
            if not isinstance(source,RunInput):raise ValueError()
            run=UUID(str(run_id));snapshot=UUID(str(snapshot_id))
            if str(snapshot)!=str(snapshot_id):raise ValueError()
            for digest in (source_hash,query_sha256):
                if type(digest) is not str or not re.fullmatch(SHA_PATTERN,digest):raise ValueError()
            if type(embedding_model) is not str or not re.fullmatch(MODEL_PATTERN,embedding_model):raise ValueError()
            if type(top_k) is not int or not 1<=top_k<=5 or type(vector) not in (list,tuple):raise ValueError()
            checked=RuntimeEmbeddingClient._vector(list(vector))
            if doc_ids is not None:
                if (type(doc_ids) is not list or len(doc_ids)>200
                        or any(type(item) is not int or not 1<=item<=9223372036854775807 for item in doc_ids)):raise ValueError()
                doc_ids=list(dict.fromkeys(doc_ids))
            body=json.dumps(dict(snapshot_id=str(snapshot),query_sha256=query_sha256,embedding_model=embedding_model,
                vector=list(checked),top_k=top_k,doc_ids=doc_ids),separators=(',',':'),allow_nan=False).encode('utf-8')
        except (ValueError,TypeError,OverflowError,UnicodeError,AttributeError):
            raise ValueError('Invalid trusted knowledge query') from None
        request_hash=hashlib.sha256(body).hexdigest()
        status,raw=await self._exchange('POST',source,run,'knowledge/search',body,prefix='KNOWLEDGE',limit=65536,
                                       accepted=(200,404,409,410,413,422))
        return self._decode(status,raw,source,run,snapshot,source_hash,request_hash,query_sha256,embedding_model,top_k,doc_ids)

    @staticmethod
    def _decode(status,raw,source,run,snapshot,source_hash,request_hash,query_hash,model,top_k,doc_ids):
        def unique(pairs):
            result={}
            for key,value in pairs:
                if key in result:raise ValueError()
                result[key]=value
            return result
        def invalid_constant(_):raise ValueError()
        try:
            root=json.loads(raw,object_pairs_hook=unique,parse_constant=invalid_constant)
            required={'code','message','data'}
            if (type(root) is not dict or not required<=set(root) or set(root)-required-{'requestId','timestamp'}
                    or type(root['code']) is not int):raise ValueError()
            if 'requestId' in root and root['requestId'] is not None and (type(root['requestId']) is not str or len(root['requestId'])>128):raise ValueError()
            if 'timestamp' in root:
                stamp=root['timestamp']
                # The application serializes Java long as canonical decimal
                # strings; standalone MVC/fixture envelopes may use JSON ints.
                if type(stamp) is str and re.fullmatch(r'0|[1-9][0-9]{0,18}',stamp):stamp=int(stamp)
                if type(stamp) is not int or not 0<=stamp<=9223372036854775807:raise ValueError()
            if root['code']!=0:
                codes={40402:(404,'KNOWLEDGE_SOURCE_REVOKED'),40902:(409,'KNOWLEDGE_SOURCE_CHANGED'),
                    40905:(409,'KNOWLEDGE_SNAPSHOT_MISMATCH'),41002:(410,'KNOWLEDGE_SOURCE_EXPIRED'),
                    41302:(413,'KNOWLEDGE_SOURCE_TOO_LARGE'),42202:(422,'KNOWLEDGE_SOURCE_INVALID'),42205:(422,'KNOWLEDGE_RESULT_INVALID')}
                expected=codes.get(root['code'])
                if expected is None or status!=expected[0] or root['data'] is not None:raise ValueError()
                raise PermanentFailure(expected[1])
            if status!=200:raise ValueError()
            value=root['data']
            fields={'schema_version','policy_version','tenant_code','source_event_id','run_id','snapshot_id','source_hash',
                    'request_sha256','query_sha256','embedding_model','hits','resource_version'}
            expected=dict(schema_version='knowledge-search-v1',policy_version=POLICY,tenant_code=source.tenant,
                source_event_id=str(source.source_event_id),run_id=str(run),snapshot_id=str(snapshot),source_hash=source_hash,
                request_sha256=request_hash,query_sha256=query_hash,embedding_model=model)
            if type(value) is not dict or set(value)!=fields or any(value[key]!=item for key,item in expected.items()):raise ValueError()
            if type(value['resource_version']) is not str or not re.fullmatch(SHA_PATTERN,value['resource_version']):raise ValueError()
            hits=value['hits']
            if type(hits) is not list or len(hits)>top_k or (doc_ids==[] and hits):raise ValueError()
            seen=set();last_score=1_000_000
            for hit in hits:
                if type(hit) is not dict or set(hit)!={'doc_id','chunk_id','title','snippet','score_ppm','embedding_model'}:raise ValueError()
                for key in ('doc_id','chunk_id'):
                    item=hit[key]
                    if type(item) is not str or not re.fullmatch(r'[1-9][0-9]{0,18}',item) or int(item)>9223372036854775807:raise ValueError()
                pair=(hit['doc_id'],hit['chunk_id'])
                if pair in seen or (doc_ids is not None and int(hit['doc_id']) not in doc_ids):raise ValueError()
                seen.add(pair)
                if type(hit['score_ppm']) is not int or not -1_000_000<=hit['score_ppm']<=last_score:raise ValueError()
                last_score=hit['score_ppm']
                if type(hit['title']) is not str or len(hit['title'])>200:raise ValueError()
                hit['title'].encode('utf-8')
                if (type(hit['snippet']) is not str or not hit['snippet'].strip() or len(hit['snippet'])>1024
                        or len(hit['snippet'].encode('utf-8'))>4096 or hit['embedding_model']!=model):raise ValueError()
            if not hmac.compare_digest(knowledge_digest(value),value['resource_version']):raise ValueError()
            return KnowledgeSearch(source.source_event_id,run,snapshot,source_hash,request_hash,query_hash,model,
                                   tuple(KnowledgeHit(**hit) for hit in hits),value['resource_version'])
        except (ValueError,TypeError,KeyError,UnicodeError,RecursionError,AttributeError,OverflowError):
            raise PermanentFailure('KNOWLEDGE_PAYLOAD_INVALID') from None
