package com.payrecon.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 回调应答体。
 *
 * <p>注意：本接口<b>永远</b>返回 HTTP 200 且 {@code success=true}，业务细节放在
 * {@code result} / {@code credited} / {@code message} 里给测试与排障看。
 * 渠道只认"成功/失败"，返回失败会触发无休止重试（规格 §0.5）。
 *
 * <p>刻意不含任何金额字段，避免引入 float/double 表示金额。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CallbackAck {

    /** 是否应答成功。本端点恒为 true。 */
    private boolean success;

    /** 业务处理结果：VALID_ADVANCED / DUPLICATE_IGNORED / ILLEGAL_TRANSITION / ORDER_NOT_FOUND / REJECTED。 */
    private String result;

    /** 本次是否发生了入账。 */
    private boolean credited;

    /** 人类可读的补充说明。 */
    private String message;

    /**
     * 成功应答的静态工厂。
     *
     * @param result   业务处理结果
     * @param credited 是否入账
     * @param message  补充说明
     * @return 始终 {@code success=true} 的应答体
     */
    public static CallbackAck ok(String result, boolean credited, String message) {
        return new CallbackAck(true, result, credited, message);
    }
}
