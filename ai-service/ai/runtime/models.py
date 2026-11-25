"""Explicit host model profile, one reserved MODEL per bounded HTTP attempt.

No automatic provider selection, Workflow wrapper, retry or second usage ledger.
Successful provider usage is part of the same atomic model-output checkpoint.
"""
import asyncio
from dataclasses import dataclass,field,replace
import json

from ..core.chat_transport import ModelCallFailure,_configuration
from ..core.llm_chat import call_chat
from .model_usage import checkpoint_usage
from .payloads import encode
from .types import tenant_code,identifier,fingerprint
from .worker import PermanentFailure,TransientFailure


@dataclass(frozen=True)
class ChatProfile:
    tenant: str
    profile_ref: str
    provider: str
    model: str
    base_url: str
    api_key: str = field(repr=False)
    prompt_version: str = 'planner-v1'
    max_output_tokens: int = 512
    timeout_seconds: float = 10

    def __post_init__(self):
        tenant_code(self.tenant)
        for value,limit in ((self.profile_ref,64),(self.provider,32),(self.model,128),(self.prompt_version,32)):
            identifier(value,limit)
        if type(self.max_output_tokens) is not int or not 1<=self.max_output_tokens<=4096:
            raise ValueError('Invalid model output budget')
        if type(self.timeout_seconds) not in (int,float) or not .05<=self.timeout_seconds<=30:
            raise ValueError('Invalid runtime model deadline')
        if not isinstance(self.api_key,str) or len(self.api_key)<16:
            raise ValueError('Runtime model credential is not configured')
        try:_configuration(self.chosen(),self.timeout_seconds)
        except ModelCallFailure:raise ValueError('Invalid runtime model configuration') from None

    def chosen(self):
        return dict(provider=self.provider,model=self.model,base_url=self.base_url,api_key=self.api_key)

    def definition_hash(self):
        return fingerprint(dict(tenant=self.tenant,profile=self.profile_ref,provider=self.provider,model=self.model,
            endpoint_sha256=fingerprint(self.base_url),prompt=self.prompt_version,max_output_tokens=self.max_output_tokens,
            timeout_seconds=self.timeout_seconds))


@dataclass(frozen=True)
class ChatResult:
    content: str = field(repr=False)
    profile_ref: str
    prompt_version: str
    request_hash: str
    input_tokens: int | None
    output_tokens: int | None


class RuntimeChatClient:
    def __init__(self,profile: ChatProfile,*,transport=None):
        if not isinstance(profile,ChatProfile):raise ValueError('Explicit host model profile required')
        self.profile,self._transport=profile,transport

    def with_prompt_version(self, version: str):
        if version == self.profile.prompt_version:
            return self
        return RuntimeChatClient(replace(self.profile, prompt_version=version), transport=self._transport)

    @staticmethod
    def _messages(messages):
        try:
            if type(messages) is not list or not 1<=len(messages)<=32:raise ValueError()
            for index,item in enumerate(messages):
                if type(item) is not dict or set(item)!={'role','content'}:raise ValueError()
                if item['role'] not in (('system',) if index==0 else ('user','assistant')):raise ValueError()
                if type(item['content']) is not str or not item['content'].strip() or len(item['content'].encode())>16384:raise ValueError()
            raw,_=encode('working-state-v1',{'messages':messages})
            if len(raw.encode())>32768:raise ValueError()
            return json.loads(raw)['messages']
        except (ValueError,TypeError,UnicodeError):
            raise PermanentFailure('MODEL_CONTEXT_INVALID') from None

    def _content(self,value):
        if (type(value) is not str or not value.strip() or len(value.encode('utf-8'))>8192
                or '\0' in value or self.profile.api_key in value):
            raise ValueError()
        return value

    @staticmethod
    def _usage(usage):
        if usage is None:return {'available':False}
        if type(usage) is not dict:raise ValueError()
        if 'prompt_tokens' not in usage or 'completion_tokens' not in usage:return {'available':False}
        values=(usage['prompt_tokens'],usage['completion_tokens'])
        if any(type(value) is not int or not 0<=value<=9223372036854775807 for value in values):raise ValueError()
        return dict(available=True,input_tokens=values[0],output_tokens=values[1])

    def _result(self,value,request_hash):
        try:
            fields={'schema_version','content','profile_ref','profile_hash','prompt_version','request_hash','usage'}
            if (type(value) is not dict or set(value)!=fields or value['schema_version']!='chat-result-v1'
                    or value['profile_ref']!=self.profile.profile_ref or value['profile_hash']!=self.profile.definition_hash()
                    or value['prompt_version']!=self.profile.prompt_version or value['request_hash']!=request_hash):raise ValueError()
            content=self._content(value['content'])
            counts=checkpoint_usage(value['usage'],chat=True)
            return ChatResult(content,self.profile.profile_ref,self.profile.prompt_version,request_hash,*counts)
        except (ValueError,TypeError,KeyError,UnicodeError):
            raise PermanentFailure('MODEL_CHECKPOINT_INVALID') from None

    def complete(self,context,key,messages):
        if (self.profile.tenant!=context.lease.tenant or context.run['tenant_code']!=context.lease.tenant
                or context.run['run_id']!=context.lease.run_id):
            raise PermanentFailure('MODEL_PROFILE_DENIED')
        messages=self._messages(messages)
        payload=dict(model=self.profile.model,messages=messages,max_tokens=self.profile.max_output_tokens,temperature=0,stream=False,n=1)
        digest=fingerprint(payload)
        intent=dict(operation='model.chat',model=self.profile.profile_ref,version=self.profile.prompt_version,
                    input_ref=self.profile.definition_hash(),arguments_sha256=digest)
        def execute():
            try:
                data=asyncio.run(call_chat(self.profile.chosen(),payload,self.profile.timeout_seconds,
                    scene='Agent Runtime',tenant_code=context.lease.tenant,meter_usage=False,_transport=self._transport))
            except ModelCallFailure as error:
                failure=TransientFailure if error.retryable else PermanentFailure
                raise failure(error.code) from None
            try:
                choices=data['choices']
                if type(choices) is not list or len(choices)!=1:raise ValueError()
                choice=choices[0]
                if type(choice) is not dict or choice.get('finish_reason')!='stop':raise ValueError()
                if 'index' in choice and (type(choice['index']) is not int or choice['index']!=0):raise ValueError()
                message=choice['message']
                if (type(message) is not dict or message.get('role')!='assistant'
                        or message.get('tool_calls') or message.get('refusal')):raise ValueError()
                content=self._content(message['content']);usage=self._usage(data.get('usage'))
            except (ValueError,TypeError,KeyError,UnicodeError):
                raise PermanentFailure('MODEL_OUTPUT_INVALID') from None
            value=dict(schema_version='chat-result-v1',content=content,usage=usage,profile_ref=self.profile.profile_ref,
                       profile_hash=self.profile.definition_hash(),prompt_version=self.profile.prompt_version,request_hash=digest)
            self._result(value,digest)
            return value
        value=context.payload_step(key,'MODEL','NONE',intent,'model-output-v1',execute)
        return self._result(value,digest)
