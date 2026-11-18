package io.supportflow.customer.controller.ticket;

import io.supportflow.common.util.Texts;
import io.supportflow.customer.dto.ticket.SurveyOutcome;
import io.supportflow.customer.nativechannel.TicketFollowUpService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工单回访评价页：客户在**邮件里点开的那个链接**（免登录）。
 *
 * <p>为什么直接返回 HTML 而不是 JSON：收信人点开的是邮件正文里的链接，落地的必须是一个
 * 「打开就能打分」的页面，不能要求他先登录控制台、也不能指望邮件客户端跑前端路由。
 * 所以这里自渲染一张极简页面——五个分数按钮 + 一句感谢，无脚本、无外部资源，
 * 邮件客户端与手机浏览器都能正常打开。</p>
 *
 * <p>链接安全：{@code sig} 是对 (租户, 工单号) 的 HMAC 签名。工单号是连号/可猜的，
 * 只靠 {@code no} 参数的话，任何人改成别人的工单号就能替别人打分；带上签名后，
 * 猜不出密钥就伪造不出合法链接。</p>
 */
@RestController
@RequestMapping("/api/customer/tickets/survey")
public class TicketSurveyController {

    private final TicketFollowUpService followUpService;

    public TicketSurveyController(TicketFollowUpService followUpService) {
        this.followUpService = followUpService;
    }

    /**
     * 满意度回访页（工单解决后通过短信 / 邮件里的链接打开）。
     *
     * <p>无登录态，靠链接里的加密令牌绑定租户与工单号，避免被刷分。</p>
     */
    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> survey(
            @RequestParam(value = "t", required = false) String tenantCode,
            @RequestParam(value = "no", required = false) String ticketNo,
            @RequestParam(value = "sig", required = false) String signature,
            @RequestParam(value = "score", required = false) Integer score) {
        if (!followUpService.validSignature(tenantCode, ticketNo, signature)) {
            return html(403, page("链接无效", "这条评价链接不完整或已被篡改，请从回访邮件里重新点开，或联系客服。"));
        }
        SurveyOutcome outcome = followUpService.survey(tenantCode, ticketNo, score);
        return switch (outcome.state()) {
            case FORM -> html(200, formPage(outcome.message(), tenantCode, ticketNo, signature));
            case DONE -> html(200, page("评价完成", outcome.message()));
            case PENDING -> html(200, page("工单还在处理中", outcome.message()));
            case NOT_FOUND -> html(404, page("工单不存在", outcome.message()));
        };
    }

    /** 打分表单：1~5 分各一个链接（邮件里点链接比填表单可靠，不依赖 POST 与脚本） */
    private String formPage(String title, String tenantCode, String ticketNo, String signature) {
        String safeTitle = Texts.htmlEscape(title == null || title.isBlank() ? "本次服务" : title);
        String base = "/api/customer/tickets/survey?t=" + Texts.htmlEscape(tenantCode)
                + "&no=" + Texts.htmlEscape(ticketNo) + "&sig=" + Texts.htmlEscape(signature) + "&score=";
        String[] labels = {"很不满意", "不满意", "一般", "满意", "非常满意"};
        StringBuilder stars = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            stars.append("<a class=\"score\" href=\"").append(base).append(i).append("\">")
                    .append("<b>").append(i).append("</b><span>").append(labels[i - 1]).append("</span></a>");
        }
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"/>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>"
                + "<title>服务评价</title>" + STYLE + "</head><body><div class=\"card\">"
                + "<h1>请为本次服务打分</h1>"
                + "<p class=\"muted\">问题：<b>" + safeTitle + "</b></p>"
                + "<div class=\"scores\">" + stars + "</div>"
                + "<p class=\"tip\">1 分 = 很不满意，5 分 = 非常满意。您的评价会直接反馈给服务团队。</p>"
                + "</div></body></html>";
    }

    private static String page(String heading, String message) {
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"/>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>"
                + "<title>" + Texts.htmlEscape(heading) + "</title>" + STYLE + "</head><body><div class=\"card\">"
                + "<h1>" + Texts.htmlEscape(heading) + "</h1><p class=\"muted\">" + Texts.htmlEscape(message) + "</p>"
                + "<p class=\"tip\">SupportFlow 智能客服</p></div></body></html>";
    }

    private static ResponseEntity<String> html(int status, String body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    /** 极简内联样式：不引外部资源（邮件里点开的页面，外链图片/字体经常被客户端拦） */
    private static final String STYLE = "<style>"
            + "*{box-sizing:border-box}body{margin:0;min-height:100vh;display:flex;align-items:center;"
            + "justify-content:center;background:#f1f5f9;font-family:-apple-system,BlinkMacSystemFont,"
            + "'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;padding:20px}"
            + ".card{width:100%;max-width:520px;background:#fff;border-radius:14px;padding:32px 28px;"
            + "box-shadow:0 8px 30px rgba(15,23,42,.08)}"
            + "h1{font-size:20px;margin:0 0 14px;color:#0f172a}"
            + ".muted{color:#475569;line-height:1.7;margin:0 0 8px;word-break:break-all}"
            + ".scores{display:flex;gap:10px;margin:22px 0 6px;flex-wrap:wrap}"
            + ".score{flex:1;min-width:84px;display:flex;flex-direction:column;align-items:center;"
            + "gap:4px;padding:14px 6px;border:1px solid #e2e8f0;border-radius:10px;text-decoration:none;"
            + "color:#0f172a;transition:.15s}"
            + ".score:hover{border-color:#2563eb;background:#eff6ff;transform:translateY(-2px)}"
            + ".score b{font-size:22px;color:#2563eb}.score span{font-size:12px;color:#64748b}"
            + ".tip{color:#94a3b8;font-size:12px;margin:18px 0 0}"
            + "</style>";

    }
