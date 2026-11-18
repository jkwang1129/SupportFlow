package io.supportflow.user.security;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * 图形验证码渲染：把验证码字符串画成 PNG，再编码成 {@code data:image/png;base64,...}
 * 的内联图片（前端直接塞进 {@code <img src>}，不用额外一个下载接口）。
 *
 * <p>抽成组件而不是塞在控制器里：绘图参数（尺寸、字号、干扰线）属于呈现细节，
 * 与接口契约无关，放一起会让控制器混进一堆 AWT 代码。</p>
 */
@Component
public class CaptchaImageRenderer {

    /** 内联图片前缀：前端把它直接当 src 用 */
    public static final String IMAGE_PREFIX = "data:image/png;base64,";

    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;

    /** 渲染验证码并把结果编码成内联 data URL */
    public String toDataUrl(String code) {
        return IMAGE_PREFIX + Base64.getEncoder().encodeToString(toPng(render(code)));
    }

    private BufferedImage render(String code) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(243, 246, 250));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        // 干扰线：让 OCR 不那么容易直接读出字符，但别糊到人眼也看不清
        g.setColor(new Color(100, 116, 139, 80));
        g.setStroke(new BasicStroke(1.2f));
        for (int i = 0; i < 5; i++) {
            int x1 = (int) (Math.random() * WIDTH);
            int y1 = (int) (Math.random() * HEIGHT);
            g.drawLine(x1, y1, x1 + 24, y1 - 18);
        }
        g.setFont(new Font("SansSerif", Font.BOLD, 24));
        char[] chars = code.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            g.setColor(new Color(30, 64, 175));
            g.drawString(String.valueOf(chars[i]), 16 + i * 24, 29);
        }
        g.dispose();
        return image;
    }

    private byte[] toPng(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("验证码图片生成失败", e);
        }
    }
}
