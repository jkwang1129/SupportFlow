"""应用配置：环境变量前缀 SUPPORTFLOW_AI_，支持 .env。"""

from __future__ import annotations

from functools import lru_cache

from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="SUPPORTFLOW_AI_", env_file=".env", extra="ignore")

    app_name: str = "SupportFlow AI"
    service_name: str = "ai-center"
    debug: bool = False
    api_prefix: str = "/api"
    gateway_secret: SecretStr = SecretStr("")
    customer_secret: SecretStr = SecretStr("")
    ops_secret: SecretStr = SecretStr("")
    tenant_secret: SecretStr = SecretStr("")
    # Receiver is opt-in until Java delivery/result integration is accepted.
    runtime_api_enabled: bool = False
    runtime_ticket_actions_enabled: bool = False
    runtime_approval_review_enabled: bool = False
    customer_runtime_url: str = 'http://127.0.0.1:9093'
    # 日志级别：DEBUG / INFO / WARNING / ERROR
    log_level: str = "INFO"
    # 是否打印 AI 调用的完整入参/出参（真实模型请求与响应原文）
    llm_log_payload: bool = False

    # LLM 网关（默认 mock；配置 QWEN/DeepSeek 后可切 qwen/deepseek）
    llm_default_provider: str = "mock"
    llm_default_model: str = ""
    llm_timeout: int = 30

    # 千问（DashScope OpenAI 兼容接口）
    qwen_api_key: str = ""
    qwen_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    qwen_model: str = "qwen-plus"
    # 视觉模型（多模态）：客户发图片时用它做 OCR + 截图判读。
    # 千问 VL 走的是同一个 DashScope OpenAI 兼容接口，只是模型名不同。
    qwen_vl_model: str = "qwen-vl-plus"
    # 单次最多识别几张图、每张图最大多少 MB（转 base64 后 payload 会涨约 1/3，必须设上限）
    vision_max_images: int = 3
    vision_max_image_mb: int = 6

    # ------------------------------------------------------------------
    # 阿里云智能语音交互（NLS）：一句话识别（ASR）+ 语音合成（TTS）
    #
    # 这套和千问（DashScope）不是同一个产品线，用的是「智能语音交互」的
    # AppKey + AccessKeyId/Secret（先 CreateToken 换 token，再调 stream/v1/asr|tts）。
    # 与「企业智能招聘系统」的接法一致，只是收口到了这里。
    #
    # 默认关闭：没配的时候语音接口返回 available=false、录音不转写，
    # 不会因为少一个配置就让 400 热线报错。
    # ------------------------------------------------------------------
    speech_enabled: bool = False
    speech_region: str = "cn-shanghai"
    # 智能语音交互项目 AppKey（控制台「智能语音交互 → 项目」里拿）
    speech_app_key: str = ""
    # 阿里云 AccessKey（RAM 用户即可，只要有权调 NLS）
    speech_access_key_id: str = ""
    speech_access_key_secret: str = ""
    # 网关与换 token 的域名：自检时会指向本地假 NLS 服务，验证请求怎么拼、错误怎么翻
    speech_gateway_base: str = "https://nls-gateway-cn-shanghai.aliyuncs.com"
    speech_meta_base: str = "https://nls-meta.cn-shanghai.aliyuncs.com"
    speech_timeout: int = 30
    # ASR 默认吃 16k 单声道 WAV（呼叫中心回传的录音多为 mp3，Java 侧会先转码/校验）
    speech_asr_format: str = "wav"
    speech_asr_sample_rate: int = 16000
    # TTS 默认知祥（磁性男声），与招聘系统一致
    speech_tts_voice: str = "zhixiang"
    speech_tts_format: str = "wav"
    speech_tts_sample_rate: int = 16000
    speech_tts_volume: int = 50
    speech_tts_speech_rate: int = 0
    speech_tts_pitch: int = 0
    # 单条音频 / 单段播报文案上限：防一次把整通话录音塞进来
    speech_max_audio_mb: int = 10
    speech_max_text_chars: int = 500

    # DeepSeek OpenAI 兼容接口
    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com/v1"
    deepseek_model: str = "deepseek-chat"

    # 向量化（知识库用）：默认走千问的 OpenAI 兼容 /embeddings 接口。
    # 不配 embedding_api_key 时自动降级为"本地哈希向量"——只能把链路跑通，语义效果差。
    embedding_provider: str = "qwen"
    embedding_api_key: str = ""
    embedding_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    embedding_model: str = "text-embedding-v3"
    embedding_timeout: int = 30

    # 知识问答的"召回够不够"阈值（量纲不同，分开配）：
    #   vector  → 余弦相似度。经验值：中文短问句和正确切片一般在 0.4~0.7，
    #             原来定 0.5 会把"确实相关但问句很短"的情况判成不够用，
    #             于是机器人明明查得到也说"资料不足"，所以降到 0.35；
    #   keyword → 命中片段占比（命中词数 / 查询片段数），基数小，0.12 起步。
    rag_vector_min_score: float = 0.35
    rag_keyword_min_score: float = 0.12

    # 训练版本做意图匹配时的相似度门槛：低于它就不采信（交回字面规则匹配）。
    # 中文短问句和正确语料的余弦相似度一般在 0.5~0.9，取 0.45 是"明显相关才认"。
    train_match_min_score: float = 0.45

    def embedding_key(self) -> str:
        """向量化用哪个密钥。

        优先专用的 ``embedding_api_key``；没配就**复用千问的 key**——
        千问的对话和向量化是同一个账号同一套 OpenAI 兼容接口，
        让用户把同一个密钥配两遍（少配一处就悄悄退化成兜底向量）是个坑。
        """
        return self.embedding_api_key or self.qwen_api_key

    # 知识库：切片参数与数据源（切片存在 customer_db，和文档表同库，租户隔离靠 tenant_code）
    kb_database_url: str = "postgresql://supportflow_kb@127.0.0.1:5432/customer_db"
    enforce_runtime_db_roles: bool = True
    # 切块大小：内置实现按"字符"算，LlamaIndex 按"token"算（同数值粒度不同，可按需分别调）
    kb_chunk_size: int = 600
    kb_chunk_overlap: int = 80
    kb_max_file_mb: int = 20

    redis_url: str = "redis://localhost:6379/0"
    kafka_bootstrap: str = "localhost:9092"

    # ai_db (SUPPORTFLOW_AI_DATABASE_URL)
    database_url: str = "postgresql://supportflow_ai@127.0.0.1:5432/ai_db"


@lru_cache
def get_settings() -> Settings:
    return Settings()
