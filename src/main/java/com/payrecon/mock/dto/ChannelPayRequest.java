package com.payrecon.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 渠道支付请求 DTO（渠道 Mock 的入参报文）。
 *
 * <p>金额一律使用 {@link BigDecimal}，禁止 float/double。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChannelPayRequest {

    /** 商户订单号。 */
    private String merchantOrderNo;

    /** 支付金额。 */
    private BigDecimal amount;
}
