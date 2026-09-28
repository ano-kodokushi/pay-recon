package com.payrecon.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 渠道回调发送请求 DTO（驱动渠道 Mock 主动回调我们的入参报文）。
 *
 * <p>金额一律使用 {@link BigDecimal}，禁止 float/double。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChannelNotifyRequest {

    /** 商户订单号。 */
    private String merchantOrderNo;

    /** 渠道交易流水号。 */
    private String tradeNo;

    /** 回调金额。 */
    private BigDecimal amount;

    /** 发送次数；为 null 时按 1 次处理，用于验证服务端回调幂等性。 */
    private Integer times;
}
