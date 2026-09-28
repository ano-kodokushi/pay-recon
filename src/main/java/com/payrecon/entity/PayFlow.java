package com.payrecon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 流水/事件表，对应表 {@code pay_flow}。
 *
 * <p>只追加，永不修改、永不删除（审计表）。
 */
@Data
@TableName("pay_flow")
public class PayFlow {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("order_id")
    private Long orderId;

    /** CREATE/CALLBACK/QUERY/RECONCILE。 */
    @TableField("event_type")
    private String eventType;

    /** {@code source} 是 MySQL 保留字，必须用反引号，否则生成的 SQL 语法错误。 */
    @TableField("`source`")
    private String source;

    @TableField("from_status")
    private String fromStatus;

    @TableField("to_status")
    private String toStatus;

    /** 渠道原始报文，原文保存。 */
    @TableField("raw_payload")
    private String rawPayload;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
