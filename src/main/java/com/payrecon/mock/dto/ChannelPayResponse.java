package com.payrecon.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 渠道支付响应 DTO（渠道 Mock 的返回报文）。
 *
 * <p>{@code @NoArgsConstructor} 供 Jackson 反序列化，
 * {@code @AllArgsConstructor} 供控制器直接构造返回报文。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChannelPayResponse {

    /** 渠道交易流水号。 */
    private String tradeNo;

    /** 渠道处理状态。 */
    private String status;

    /** 商户订单号，原样回带便于调用方关联。 */
    private String merchantOrderNo;
}
