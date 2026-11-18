package io.supportflow.customer.dto.channel;

import java.util.List;

/**
 * 原生渠道配置表单里的一个字段（渠道详情接口回给前端、前端据此渲染表单）。
 *
 * <p>以前它内嵌在 {@code ChannelNativeConfigService} 里——明细是接口报文的一部分，
 * 却是"服务内部类型"，消费方（控制器、dto）都得反过来引用服务包。按项目约定挪到 dto。</p>
 *
 * @param options 只有下拉（type=select）用得上；不带它的构造器是给 text/secret/textarea/json
 *                这些不需要枚举选项的字段用的，免得每处都写一个 {@code List.of()}
 */
public record NativeField(String name, String label, String type, boolean required, boolean secret,
                          String placeholder, String help, List<String> options) {

    public NativeField(String name, String label, String type, boolean required, boolean secret,
                       String placeholder, String help) {
        this(name, label, type, required, secret, placeholder, help, List.of());
    }

    public NativeField {
        options = options == null ? List.of() : List.copyOf(options);
    }
}
