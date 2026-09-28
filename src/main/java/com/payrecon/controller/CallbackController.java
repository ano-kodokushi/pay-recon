package com.payrecon.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrecon.dto.CallbackAck;
import com.payrecon.enums.EventType;
import com.payrecon.enums.FlowSource;
import com.payrecon.enums.PayStatus;
import com.payrecon.service.AdvanceService;
import com.payrecon.service.HmacVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务 2：渠道支付回调入口。
 *
 * <p>职责边界：<b>验签 + 解析 + 委托</b>。本类不写 {@code pay_order}，
 * 不写 {@code pay_flow}，也不做任何状态判断——状态推进与流水全部由
 * {@link AdvanceService#advance} 独占，避免出现第二份推进逻辑。
 *
 * <p><b>为什么所有分支都返回 HTTP 200 + success=true（规格 §0.5）：</b>
 * 渠道侧只把非 2xx（或应答体失败）视为"投递失败"，而失败会触发渠道按固定间隔
 * <b>无限重试</b>。验签失败、报文解析失败、订单号缺失、状态非法这些情况重试一万次
 * 结果也一样，只会持续打爆我们的接口。因此这里对外统一"已收到"，把真实处理结果
 * 放在应答体的 {@code result} 字段里，由日志与流水表承担可观测性；
 * 渠道重试的幂等性由 {@code AdvanceService} 的条件更新 + 状态机保证。
 *
 * <p>因为上面的约定，本方法必须用 {@code try/catch (Exception)} 兜住一切异常。
 * 这是本项目中<b>唯一</b>允许且被规格要求写宽泛 catch 的位置，与幂等路径上
 * "禁止用 catch 吞掉唯一键冲突"的要求不冲突：那里吞异常会导致重复下单，
 * 这里吞异常只是让坏报文不触发渠道重试风暴。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class CallbackController {

    /** 验签失败（含签名为空）时返回的 result 值。 */
    private static final String RESULT_REJECTED = "REJECTED";

    private final AdvanceService advanceService;
    private final HmacVerifier hmacVerifier;
    private final ObjectMapper objectMapper;

    /**
     * 接收渠道回调。
     *
     * <p>入参刻意用原始 {@code String} 接收报文：HMAC 是对**原始字节**计算的，
     * 若先反序列化成对象再重新序列化，字段顺序、空格、转义都会变，字节一变签名必然验不过。
     *
     * @param rawBody   渠道推送的原始 JSON 报文
     * @param signature 请求头 {@code X-Signature} 中的十六进制签名，缺失时为 null
     * @return 恒为 {@code success=true} 的应答体
     */
    @PostMapping(value = "/api/pay/callback", consumes = MediaType.ALL_VALUE)
    public CallbackAck callback(@RequestBody String rawBody,
                                @RequestHeader(value = "X-Signature", required = false) String signature) {
        try {
            // ---- 1. 验签。失败即拒收，但对外仍是 200，且不推进任何状态 ----
            if (!hmacVerifier.verify(rawBody, signature)) {
                log.warn("回调验签失败，已拒绝处理（未推进状态）。signature={}", signature);
                return CallbackAck.ok(RESULT_REJECTED, false, "signature verification failed");
            }

            // ---- 2. 自行解析报文（验签已通过，可以用原始串解析，不影响签名） ----
            String merchantOrderNo = readText(rawBody, "merchantOrderNo", "merchant_order_no");
            String tradeNo = readText(rawBody, "tradeNo", "trade_no");
            String statusText = readText(rawBody, "status", "toStatus");

            if (merchantOrderNo == null || merchantOrderNo.isBlank()) {
                log.warn("回调报文缺少商户订单号，已忽略。rawBody={}", rawBody);
                return CallbackAck.ok(RESULT_REJECTED, false, "merchantOrderNo missing");
            }

            PayStatus toStatus = PayStatus.of(statusText);
            if (toStatus == null) {
                log.warn("回调报文状态无法识别，已忽略。merchantOrderNo={} status={}", merchantOrderNo, statusText);
                return CallbackAck.ok(RESULT_REJECTED, false, "unknown status: " + statusText);
            }

            // ---- 3. 委托唯一的推进入口，本控制器不做任何状态判断 ----
            AdvanceService.AdvanceResult result = advanceService.advance(
                    merchantOrderNo, toStatus, EventType.CALLBACK, FlowSource.CALLBACK, rawBody, tradeNo);

            log.info("回调处理完成 merchantOrderNo={} toStatus={} outcome={} credited={}",
                    merchantOrderNo, toStatus, result.outcome(), result.credited());

            // ---- 4. 把 Outcome 映射进应答体；仍是 200 ----
            return CallbackAck.ok(result.outcome().name(), result.credited(),
                    buildMessage(merchantOrderNo, toStatus, result));

        } catch (Exception e) {
            // 宽泛 catch 在此处是规格要求，不是坏味道：见类注释 §0.5。
            // 若不兜住，Spring 会返回 500，渠道据此判定投递失败并无限重试。
            log.error("回调处理出现未预期异常，已按成功语义应答以避免渠道无限重试。tradeNo 解析可能失败，rawBody={}", rawBody, e);
            return CallbackAck.ok(RESULT_REJECTED, false, "internal error, ignored");
        }
    }

    private String buildMessage(String merchantOrderNo, PayStatus toStatus, AdvanceService.AdvanceResult result) {
        return "merchantOrderNo=" + merchantOrderNo + ", toStatus=" + toStatus
                + ", outcome=" + result.outcome();
    }

    /**
     * 从原始报文中按多个候选字段名读取文本值，兼容下划线写法。
     *
     * @param rawBody 原始 JSON 报文
     * @param names   候选字段名，按顺序取第一个非空值
     * @return 字段值；不存在或非文本时返回 null
     */
    private String readText(String rawBody, String... names) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        JsonNode root = null;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            log.warn("回调报文不是合法 JSON：{}", e.getMessage());
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        for (String name : names) {
            JsonNode node = root.get(name);
            if (node != null && !node.isNull() && node.isValueNode()) {
                String value = node.asText();
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }
}
