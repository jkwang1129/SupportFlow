package io.supportflow.customer.wechat;

import io.supportflow.common.exception.BizException;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信消息的 XML 读写（不引第三方 XML 库）。
 *
 * <p>解析必须关掉外部实体：微信回调是公网入口，XXE 是这类入口最典型的攻击面，
 * 这里显式禁用 DOCTYPE 与外部实体，避免用 DOM 默认配置踩坑。</p>
 */
public final class WeChatXml {

    private WeChatXml() {
    }

    /** 解析微信推送的 XML，key 不加命名空间、原样保留大小写（微信字段都是首字母大写） */
    public static Map<String, String> parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new BizException(40001, "微信消息为空");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            Map<String, String> result = new LinkedHashMap<>();
            NodeList children = document.getDocumentElement().getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    result.put(node.getNodeName(), node.getTextContent());
                }
            }
            return result;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(40001, "微信消息不是合法 XML：" + e.getMessage());
        }
    }

    /** 被动回复文本消息（5 秒内直接回这个 XML；慢回复走客服消息接口） */
    public static String textReply(Map<String, String> request, String content) {
        return "<xml>"
                + "<ToUserName><![CDATA[" + cdata(request.get("FromUserName")) + "]]></ToUserName>"
                + "<FromUserName><![CDATA[" + cdata(request.get("ToUserName")) + "]]></FromUserName>"
                + "<CreateTime>" + System.currentTimeMillis() / 1000 + "</CreateTime>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<Content><![CDATA[" + cdata(content) + "]]></Content>"
                + "</xml>";
    }

    /** CDATA 里不能出现 "]]>"，真出现就拆开（微信文本里偶发，不处理会直接回包失败） */
    private static String cdata(String value) {
        return value == null ? "" : value.replace("]]>", "]]]]><![CDATA[>");
    }
}
