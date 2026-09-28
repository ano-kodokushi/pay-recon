package com.payrecon.dto;

import lombok.Data;

/**
 * 对账请求体。
 *
 * <p>只有一个账单日入参：对账的两个事实来源分别是
 * 「本地 {@code pay_order} 中该账单日创建的订单」与「渠道下发的日终对账单」，
 * 二者都以 {@code billDate} 为切片依据。</p>
 */
@Data
public class ReconcileRequest {

    /**
     * 账单日，ISO 格式 {@code yyyy-MM-dd}。
     *
     * <p>由 {@code ReconcileController} 用 {@link java.time.LocalDate#parse(CharSequence)}
     * 校验；解析失败返回 HTTP 400，不会把畸形日期带进 SQL。</p>
     */
    private String billDate;
}
