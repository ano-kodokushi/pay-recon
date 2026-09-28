package com.payrecon.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 下单请求体。
 *
 * <p>金额一律使用 {@link BigDecimal}：任何 float / double / Float / Double 在二进制浮点下
 * 都无法精确表示十进制小数，用于金额会在累加、比较时产生分位误差。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderCreateRequest {

    /** 商户订单号，幂等的唯一来源，对应 pay_order.uk_merchant_order_no。 */
    private String merchantOrderNo;

    /** 金额，单位元；禁止 float/double。 */
    private BigDecimal amount;
}
