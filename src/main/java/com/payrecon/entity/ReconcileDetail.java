package com.payrecon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 对账差异明细，对应表 {@code reconcile_detail}。
 */
@Data
@TableName("reconcile_detail")
public class ReconcileDetail {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("order_id")
    private Long orderId;

    @TableField("merchant_order_no")
    private String merchantOrderNo;

    /** 账单日期，对应 DATE 列。 */
    @TableField("bill_date")
    private LocalDate billDate;

    @TableField("local_status")
    private String localStatus;

    @TableField("channel_status")
    private String channelStatus;

    /** LOCAL_OK_CHANNEL_FAIL/CHANNEL_OK_LOCAL_FAIL/AMOUNT_MISMATCH/ONE_SIDE_ONLY。 */
    @TableField("diff_type")
    private String diffType;

    /** 对应 TINYINT，用 Integer 承载。 */
    @TableField("resolved")
    private Integer resolved;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
