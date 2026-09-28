package com.payrecon.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 下单响应体。
 *
 * <p>并发/重复提交同一个 merchantOrderNo 时，返回的 orderId 必须完全一致。</p>
 */
@Data
public class OrderCreateResponse {

    /** 订单主键；重复请求返回的是首次插入的那一笔的 id。 */
    private Long orderId;

    /** 商户订单号，原样回显。 */
    private String merchantOrderNo;

    /** 金额，来自数据库中的那一行（不是本次请求的值，避免请求值与落库值不一致）。 */
    private BigDecimal amount;

    /** 当前状态，首次创建为 CREATED。 */
    private String status;

    /**
     * 是否为复用已存在订单。
     *
     * <p>{@code true} 表示本次调用撞唯一索引、输掉了竞争，返回的是库里已有的那一笔；
     * {@code false} 表示本次调用就是插入成功的那一次（winner）。</p>
     */
    private boolean reused;
}
