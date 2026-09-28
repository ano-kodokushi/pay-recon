package com.payrecon.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 渠道查询请求 DTO（渠道 Mock 的入参报文）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChannelQueryRequest {

    /** 商户订单号。 */
    private String merchantOrderNo;
}
