package io.supportflow.customer.wechat;

import io.supportflow.common.exception.BizException;

import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * 微信公众号「服务器配置」用的加解密与验签（WXBizMsgCrypt 的等价实现）。
 *
 * <p>为什么不直接引微信官方 SDK：官方 Java SDK 只发在微信的 Maven 私服上，
 * 企业内网 / 离线环境经常拉不到，而这段算法本身只有 100 行左右，
 * 用 JDK 自带的 {@code javax.crypto} 实现可控、可测、没有额外依赖。</p>
 *
 * <p>三件事：</p>
 * <ol>
 *   <li><b>验签</b>：把 token、timestamp、nonce 三个字符串排序后拼起来做 SHA1，与微信传来的 signature 比；</li>
 *   <li><b>解密</b>：EncodingAESKey 是 43 位字符串，补一个 '=' 后 Base64 解码得到 32 字节 AES Key，
 *       前 16 字节同时作为 IV；密文是 AES-256-CBC + PKCS#7；</li>
 *   <li><b>明文格式</b>：解密出来是 {@code 16字节随机数 + 4字节消息长度(网络序) + 消息体 + AppID}，
 *       要去掉头和尾，否则 JSON/XML 前面会多出乱码。</li>
 * </ol>
 */
public final class WeChatCrypt {

    private WeChatCrypt() {
    }

    /** 验签：token + timestamp + nonce 排序后 SHA1 */
    public static boolean checkSignature(String token, String signature, String timestamp, String nonce) {
        if (token == null || signature == null || timestamp == null || nonce == null) {
            return false;
        }
        String[] parts = {token, timestamp, nonce};
        Arrays.sort(parts);
        String origin = String.join("", parts);
        return sha1Hex(origin).equalsIgnoreCase(signature);
    }

    /**
     * 安全模式报文的验签：把 token / timestamp / nonce / encrypt 四个值字典序拼成一串再 SHA1。
     *
     * <p>公众号与小程序是同一套算法（之前两个类里各抄了一份 {@code sortedJoin}），
     * 提到这里之后，改算法只需要动一处。{@code msgSignature} 为空时按"没带签名"放行，
     * 与微信明文模式的报文兼容。</p>
     */
    public static boolean msgSignatureMatches(String token, String timestamp, String nonce,
                                              String encrypt, String msgSignature) {
        if (msgSignature == null) {
            return true;
        }
        String[] parts = {token, timestamp, nonce, encrypt};
        Arrays.sort(parts);
        return sha1Hex(String.join("", parts)).equalsIgnoreCase(msgSignature);
    }

    /** SHA1 十六进制小写 */
    public static String sha1Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new BizException(50001, "计算微信签名失败：" + e.getMessage());
        }
    }

    /**
     * 解密密文（安全模式）。
     *
     * @param encodingAesKey 公众号后台的 EncodingAESKey（43 位）
     * @param encrypted      微信 POST 过来的 Encrypt 字段（Base64）
     * @param expectedAppId  用于校验明文尾部的 AppID，防止"别人家公众号的消息串进来"
     */
    public static String decrypt(String encodingAesKey, String encrypted, String expectedAppId) {
        byte[] aesKey = aesKeyOf(encodingAesKey);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                    new IvParameterSpec(Arrays.copyOfRange(aesKey, 0, 16)));
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(encrypted));
            return extract(plain, expectedAppId);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(40001, "微信消息解密失败，请检查 EncodingAESKey 是否与公众号后台一致");
        }
    }

    /** 加密（回复消息走安全模式时需要；随机数与长度头按微信要求补齐） */
    public static String encrypt(String encodingAesKey, String plaintext, String appId) {
        byte[] aesKey = aesKeyOf(encodingAesKey);
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        byte[] body = plaintext.getBytes(StandardCharsets.UTF_8);
        byte[] appIdBytes = appId.getBytes(StandardCharsets.UTF_8);
        byte[] plain = new byte[16 + 4 + body.length + appIdBytes.length];
        System.arraycopy(random, 0, plain, 0, 16);
        int length = body.length;
        plain[16] = (byte) ((length >> 24) & 0xFF);
        plain[17] = (byte) ((length >> 16) & 0xFF);
        plain[18] = (byte) ((length >> 8) & 0xFF);
        plain[19] = (byte) (length & 0xFF);
        System.arraycopy(body, 0, plain, 20, body.length);
        System.arraycopy(appIdBytes, 0, plain, 20 + body.length, appIdBytes.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                    new IvParameterSpec(Arrays.copyOfRange(aesKey, 0, 16)));
            return Base64.getEncoder().encodeToString(
                    cipher.doFinal(pkcs7Pad(plain, 32)));
        } catch (Exception e) {
            throw new BizException(50001, "微信消息加密失败：" + e.getMessage());
        }
    }

    /** EncodingAESKey（43 位）→ 32 字节 AES Key */
    public static byte[] aesKeyOf(String encodingAesKey) {
        if (encodingAesKey == null || encodingAesKey.length() != 43) {
            throw new BizException(40001, "EncodingAESKey 必须是 43 位字符");
        }
        try {
            return Base64.getDecoder().decode(encodingAesKey + "=");
        } catch (IllegalArgumentException e) {
            throw new BizException(40001, "EncodingAESKey 不是合法的 Base64 字符");
        }
    }

    /**
     * 去掉 PKCS#7 填充，再切出 16 字节随机数 + 4 字节长度头 + 消息体 + 尾部 AppID。
     *
     * <p>顺序不能反：密文解出来是"带填充的"，填充字节会被当成 AppID 尾巴的一部分，
     * 直接按长度截取就会校验失败（这里被交叉验证抓到过一次）。</p>
     */
    private static String extract(byte[] plain, String expectedAppId) {
        if (plain.length < 20) {
            throw new BizException(40001, "微信消息解密结果不完整");
        }
        int padding = plain[plain.length - 1] & 0xFF;
        if (padding < 1 || padding > 32) {
            throw new BizException(40001, "微信消息填充字节非法，请检查 EncodingAESKey 是否正确");
        }
        int end = plain.length - padding;
        for (int i = end; i < plain.length; i++) {
            if ((plain[i] & 0xFF) != padding) {
                throw new BizException(40001, "微信消息填充内容不一致，请检查 EncodingAESKey 是否正确");
            }
        }
        int length = ((plain[16] & 0xFF) << 24) | ((plain[17] & 0xFF) << 16)
                | ((plain[18] & 0xFF) << 8) | (plain[19] & 0xFF);
        if (length < 0 || length > end - 20) {
            throw new BizException(40001, "微信消息长度非法，请检查 EncodingAESKey");
        }
        String content = new String(plain, 20, length, StandardCharsets.UTF_8);
        String appId = new String(plain, 20 + length, end - 20 - length, StandardCharsets.UTF_8);
        if (expectedAppId != null && !expectedAppId.isBlank() && !expectedAppId.equals(appId)) {
            throw new BizException(40301, "微信消息的 AppID 与渠道配置不一致");
        }
        return content;
    }

    /** PKCS#7 填充（微信要求按 32 字节块） */
    private static byte[] pkcs7Pad(byte[] source, int blockSize) {
        int padding = blockSize - (source.length % blockSize);
        if (padding == 0) {
            padding = blockSize;
        }
        byte[] result = Arrays.copyOf(source, source.length + padding);
        Arrays.fill(result, source.length, result.length, (byte) padding);
        return result;
    }
}
