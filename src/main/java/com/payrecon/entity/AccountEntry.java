package com.payrecon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 入账流水，对应表 {@code account_entry}。
 *
 * <p>uk_order_id 是「同一订单只能入账一次」的数据库级保证。
 */
@Data
@TableName("account_entry")
public class AccountEntry {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("order_id")
    private Long orderId;

    /** 入账金额，单位元；禁止 float/double。 */
    @TableField("amount")
    private BigDecimal amount;

    /** CREDIT 入账。 */
    @TableField("entry_type")
    private String entryType;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
