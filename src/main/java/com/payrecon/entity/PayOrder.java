package com.payrecon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付订单，对应表 {@code pay_order}。
 *
 * <p>status 使用 String（而非枚举）以简化 MyBatis 映射，取值：
 * CREATED / PAYING / SUCCESS / FAILED / CLOSED。
 */
@Data
@TableName("pay_order")
public class PayOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 商户订单号，对应 uk_merchant_order_no，幂等的唯一来源。 */
    @TableField("merchant_order_no")
    private String merchantOrderNo;

    /** 金额，单位元；禁止 float/double。 */
    @TableField("amount")
    private BigDecimal amount;

    /** CREATED/PAYING/SUCCESS/FAILED/CLOSED。 */
    @TableField("status")
    private String status;

    /** 渠道流水号。 */
    @TableField("channel_trade_no")
    private String channelTradeNo;

    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 仅在同一次成功跃迁时写入，之后不再覆盖。 */
    @TableField("paid_at")
    private LocalDateTime paidAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
