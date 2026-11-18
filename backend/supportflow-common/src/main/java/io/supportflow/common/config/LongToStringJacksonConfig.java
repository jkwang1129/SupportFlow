package io.supportflow.common.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 统一 JSON 序列化规范：Long 统一输出为字符串。
 *
 * <p>主键采用雪花算法生成，为 18 位左右的 Long；前端 JavaScript 的
 * Number.MAX_SAFE_INTEGER 只能精确表示 2^53-1，直接返回 JSON Number
 * 会造成精度丢失，进而出现“资源不存在”等问题。通过全局 Jackson 定制，
 * 所有接口返回的 Long 都自动转为字符串，一次改造覆盖全部微服务。</p>
 */
@Configuration(proxyBeanMethods = false)
public class LongToStringJacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer longToStringJacksonCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule("supportflow-long-to-string");
            module.addSerializer(Long.class, ToStringSerializer.instance);
            module.addSerializer(Long.TYPE, ToStringSerializer.instance);
            builder.addModule(module);
        };
    }
}
