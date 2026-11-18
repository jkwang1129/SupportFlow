package io.supportflow.ops.dto;

import java.util.List;
import java.util.Map;

/**
 * 计量状态：AI 服务当前用的是真模型还是降级（没配密钥时页面要给出准确解释）。
 *
 * @param providers 厂商名 → 是否配了密钥（qwen / deepseek，以后接第三家不用改前端），
 *                  键是数据不是字段，所以保持 Map
 */
public record AiLlmStatusVO(Boolean llmEnabled, Map<String, Boolean> providers,
                            List<String> enabledProviders, String defaultProvider,
                            String qwenModel, String deepseekModel, String lastRealCallAt,
                            Integer todayRealCalls) {
}
