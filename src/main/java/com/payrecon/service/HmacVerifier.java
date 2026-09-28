package com.payrecon.service;

import com.payrecon.config.CallbackProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 渠道回调的 HMAC-SHA256 验签器。
 *
 * <p>签名口径（渠道 mock 与本类必须完全一致）：
 * <pre>
 *   hex( HMAC-SHA256( key = secret.getBytes(UTF_8),
 *                     message = rawBody.getBytes(UTF_8) ) )
 * </pre>
 * 输出小写十六进制字符串；<b>不拼接时间戳、不加盐</b>，因此 {@link #sign(String)}
 * 是确定性的，测试可离线复现同一个签名。
 */
@Component
@RequiredArgsConstructor
public class HmacVerifier {

    private static final String ALGORITHM = "HmacSHA256";

    private final CallbackProperties callbackProperties;

    /**
     * 校验签名。
     *
     * @param rawBody       渠道回调的原始报文（必须是与验签时逐字节相同的内容）
     * @param signatureHex  渠道携带的十六进制签名，可为 null
     * @return 签名匹配返回 true；签名为空、格式非法或密钥缺失时返回 false
     */
    public boolean verify(String rawBody, String signatureHex) {
        if (rawBody == null || signatureHex == null || signatureHex.isBlank()) {
            return false;
        }
        byte[] expected = signToBytes(rawBody);
        if (expected == null) {
            return false;
        }
        byte[] actual = decodeHex(signatureHex.trim());
        if (actual == null) {
            return false;
        }
        // 必须用恒定时间比较：如果先用 String.equals 比十六进制串，比较会在第一个不同的
        // 字符处提前返回，攻击者可以据此逐字节爆破签名（时序侧信道）。
        // MessageDigest.isEqual 对全部字节做等长比较，耗时与差异位置无关。
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * 计算报文的十六进制签名。确定性、无时间戳，供测试签名请求体使用。
     *
     * @param rawBody 原始报文
     * @return 小写十六进制签名；密钥未配置或算法不可用时返回 null
     */
    public String sign(String rawBody) {
        byte[] bytes = signToBytes(rawBody);
        return bytes == null ? null : encodeHex(bytes);
    }

    private byte[] signToBytes(String rawBody) {
        String secret = callbackProperties.getSecret();
        if (rawBody == null || secret == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 密钥或算法异常时不抛给调用方：回调入口抛异常会被渠道视为失败并无限重试
            return null;
        }
    }

    private static String encodeHex(byte[] bytes) {
        char[] hexDigits = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = hexDigits[v >>> 4];
            out[i * 2 + 1] = hexDigits[v & 0x0F];
        }
        return new String(out);
    }

    private static byte[] decodeHex(String hex) {
        int len = hex.length();
        if (len == 0 || (len & 1) != 0) {
            return null;
        }
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
