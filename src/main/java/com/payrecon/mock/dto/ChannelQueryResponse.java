package com.payrecon.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 渠道查询响应 DTO。
 *
 * <p>本类<b>同时</b>用作控制器的查询响应，以及（仅填充 {@code merchantOrderNo} 时）的查询请求体。
 * 这种复用是有意为之且代价极低：查询请求本就只需要商户订单号，为一个字段再建一个类不值得，
 * 也<b>不引入任何对象映射库</b>。金额一律使用 {@link BigDecimal}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChannelQueryResponse {

    /** 商户订单号。 */
    private String merchantOrderNo;

    /** 渠道侧订单状态；渠道无此单时可为 {@code NOT_FOUND}。 */
    private String status;

    /** 渠道交易流水号。 */
    private String tradeNo;

    /** 渠道侧金额。 */
    private BigDecimal amount;
}
