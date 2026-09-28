package com.payrecon.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrecon.service.HmacVerifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 渠道 Mock 的<b>出站回调发送器</b>（可故障注入的 mock channel notifier）。
 *
 * <p><b>这是一个可以被注入故障的模拟渠道，而不是真实渠道对接。</b>它唯一的职责是：把我们自己的
 * 回调报文，用真实的 HTTP 请求发回我们自己的回调端点。
 *
 * <p>为什么故意走真实 HTTP，而不是直接调用本地 Service：场景 2、场景 3 需要验证的是
 * <b>完整端到端回调链路</b>——包括原始报文体、{@code X-Signature} 头、以及服务端基于原始报文
 * 的 HMAC 验签。如果这里改成直接调用 Service，验签环节就被绕过了，签名口径的错误将永远测不出来。
 * 因此本类坚持发真实请求，即使在同一进程内也如此。
 *
 * <p>故障注入能力：
 * <ul>
 *   <li><b>丢回调</b>：{@link ChannelBillStore#isDropCallback()} 为 true 时直接返回 0，一个包都不发；</li>
 *   <li><b>重复回调</b>：{@code times} 参数控制重发次数，用于验证服务端的幂等性；</li>
 *   <li><b>发送失败容忍</b>：单次发送抛异常只记录 ERROR 并继续，绝不把异常抛给调用方，
 *       以免模拟渠道的故障把整个测试打挂。</li>
 * </ul>
 */
@Slf4j
@Component
public class ChannelNotifier {

    /** 单次回调报文的固定业务状态：渠道 Mock 只推送成功回调。 */
    private static final String CALLBACK_STATUS = "SUCCESS";

    private final ChannelMockProperties properties;

    private final HmacVerifier hmacVerifier;

    private final ChannelBillStore store;

    private final ObjectMapper objectMapper;

    /** 连接/读取超时均固定为 10 秒，避免回调挂死阻塞测试线程。 */
    private final RestClient restClient;

    public ChannelNotifier(ChannelMockProperties properties,
                           HmacVerifier hmacVerifier,
                           ChannelBillStore store,
                           ObjectMapper objectMapper,
                           RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.hmacVerifier = hmacVerifier;
        this.store = store;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder
                .requestFactory(buildRequestFactory())
                .build();
    }

    /**
     * 向本应用的回调端点发送回调通知。
     *
     * <p>报文只构建一次，并对<b>该精确字符串</b>签名；随后每次重发都使用同一个字节序列，
     * 保证重放报文的签名始终有效（这正是幂等性测试需要的前提）。
     *
     * <p>{@code times <= 0} 时<b>直接返回 0 且不发送任何请求</b>：测试需要能够断言“零次发送”，
     * 因此这里不做“至少发一次”的下限兜底。
     *
     * @param merchantOrderNo 商户订单号
     * @param tradeNo         渠道交易流水号
     * @param amount          回调金额，仅使用 {@link BigDecimal}，绝不经过 double 转换
     * @param times           发送次数；小于 1 时视为 0 次，直接返回 0
     * @return 实际成功完成的发送次数（丢回调、参数非法或全部失败时为 0）
     */
    public int notify(String merchantOrderNo, String tradeNo, BigDecimal amount, int times) {
        if (store.isDropCallback()) {
            log.warn("丢回调开关打开，本次不发送回调");
            return 0;
        }
        if (times <= 0) {
            log.info("回调发送次数为 {}，本次不发送回调：{}", times, merchantOrderNo);
            return 0;
        }

        String body;
        try {
            body = buildBody(merchantOrderNo, tradeNo, amount);
        } catch (Exception e) {
            log.error("回调报文序列化失败，本次不发送回调：{}", merchantOrderNo, e);
            return 0;
        }
        String signature = hmacVerifier.sign(body);
        String url = properties.effectiveCallbackUrl();

        int sent = 0;
        for (int i = 1; i <= times; i++) {
            try {
                log.info("第 {} 次发送渠道回调：{} -> {}", i, merchantOrderNo, url);
                restClient.post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", signature)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
                sent++;
            } catch (Exception e) {
                // 单次失败不中断：模拟渠道的异常不能把测试整体打挂，最终只回报成功次数
                log.error("第 {} 次发送渠道回调失败：{}", i, merchantOrderNo, e);
            }
        }
        return sent;
    }

    /**
     * 构建回调报文体。
     *
     * <p>使用 {@link LinkedHashMap} + {@link ObjectMapper}：{@code amount} 以
     * {@link BigDecimal} 对象放入 map，Jackson 直接按十进制写出其 {@code toString()} 形式，
     * <b>全程不经过 double</b>，因此 {@code 12.34} 这类金额不会被改写成 {@code 12.340000000000001}。
     * 字段顺序固定为 merchantOrderNo / tradeNo / amount / status，便于测试断言原始报文。
     *
     * @return 回调报文的 JSON 字符串
     */
    private String buildBody(String merchantOrderNo, String tradeNo, BigDecimal amount) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("merchantOrderNo", merchantOrderNo);
        payload.put("tradeNo", tradeNo);
        payload.put("amount", amount);
        payload.put("status", CALLBACK_STATUS);
        return objectMapper.writeValueAsString(payload);
    }

    /**
     * 构造带 10 秒连接/读取超时的请求工厂。
     *
     * <p>使用 JDK HttpClient 的实现：连接超时走 {@code HttpClient.connectTimeout}（构造期设定），
     * 读取超时走 {@code setReadTimeout(Duration)}。二者均固定 10 秒，
     * 避免回调挂死阻塞测试线程。
     *
     * @return 请求工厂
     */
    private static ClientHttpRequestFactory buildRequestFactory() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }
}
