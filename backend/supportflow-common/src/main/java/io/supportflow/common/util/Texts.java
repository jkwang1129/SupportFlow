package io.supportflow.common.util;

/**
 * 文本小工具：各处都在抄的那几个字符串处理。
 *
 * <p>抽出来的标准很朴素——<b>同一个方法在多个服务里各写了一份、且语义完全一致</b>。
 * 特别是脱敏（手机号 / 邮箱）与 CSV 转义：一旦各写各的，就会出现"这个页面脱敏成
 * {@code 138****8888}、那个页面漏成 {@code 13812348888}"这种事故。</p>
 *
 * <p>只做无状态、无依赖的纯文本处理；带业务的格式化（金额、时间、渠道类型文案）不进来。</p>
 */
public final class Texts {

    /** 手机号掩码位：11 位手机号保留前 3 后 4 */
    private static final int PHONE_HEAD = 3;
    private static final int PHONE_TAIL = 4;

    private Texts() {
    }

    /** 空白串（含全空格）当没有：入参查库前统一过一道，省得写出 {@code = ''} 的查询 */
    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 手机号脱敏：{@code 13812348888} → {@code 138****8888}。
     *
     * <p>太短（< 7 位）或没有值就原样返回，不做半截替换——那种"改了个寂寞"的掩码更危险。</p>
     */
    public static String maskPhone(String phone) {
        if (phone == null) {
            return "";
        }
        if (phone.length() < PHONE_HEAD + PHONE_TAIL) {
            return phone;
        }
        return phone.substring(0, PHONE_HEAD) + "****" + phone.substring(phone.length() - PHONE_TAIL);
    }

    /**
     * 邮箱脱敏：{@code zhangsan@example.com} → {@code zh***@example.com}。
     *
     * <p>本地部分只留前 2 位（不足 2 位留 1 位）再补 {@code ***}，域名不脱敏——
     * 让人能认出"是不是自己那封信"，但认不出完整账号。</p>
     */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            return "";
        }
        int at = email.indexOf('@');
        String head = email.substring(0, at);
        String domain = email.substring(at);
        String maskedHead = head.length() <= 2 ? head.charAt(0) + "***" : head.substring(0, 2) + "***";
        return maskedHead + domain;
    }

    /**
     * CSV 单元格转义：含逗号 / 引号 / 换行就整体加引号，内部引号翻倍。
     *
     * <p>导出报表统一走它——少转义一个字段，Excel 里整行就会错位。</p>
     */
    public static String csv(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (text.isEmpty()) {
            return "";
        }
        String escaped = text.replace("\"", "\"\"");
        return text.contains(",") || text.contains("\"") || text.contains("\n")
                ? "\"" + escaped + "\"" : escaped;
    }

    /**
     * HTML 转义：把用户内容拼进 HTML 正文（邮件、回执页）之前必须过一道。
     *
     * <p>顺序有讲究：先转 {@code &}，否则后面转出来的 {@code &amp;} 会被二次转义。</p>
     */
    public static String htmlEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
