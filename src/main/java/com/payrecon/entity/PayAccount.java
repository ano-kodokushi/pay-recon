package com.payrecon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 我方账户（单账户模型，id 固定为 1），对应表 {@code pay_account}。
 */
@Data
@TableName("pay_account")
public class PayAccount {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("account_no")
    private String accountNo;

    /** 账户余额，单位元；禁止 float/double。 */
    @TableField("balance")
    private BigDecimal balance;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
