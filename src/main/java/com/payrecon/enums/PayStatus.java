package com.payrecon.enums;

/**
 * 支付单状态。
 *
 * <p>状态迁移的唯一权威定义在 {@link com.payrecon.state.OrderStateMachine}，本枚举只描述状态本身，
 * 不承载任何迁移判断逻辑。</p>
 */
public enum PayStatus {

    /** 已创建，尚未发起支付。 */
    CREATED,

    /** 支付中，已向渠道发起请求，等待结果。 */
    PAYING,

    /** 支付成功（终态）。 */
    SUCCESS,

    /** 支付失败（终态）。 */
    FAILED,

    /** 已关闭（终态，例如超时关闭）。 */
    CLOSED;

    /**
     * 是否为终态。终态没有出边，不可再迁移。
     *
     * @return SUCCESS / FAILED / CLOSED 返回 true，其余返回 false
     */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CLOSED;
    }

    /**
     * 宽松解析状态字符串：大小写不敏感，null 或未知值返回 {@code null}。
     *
     * <p>这里刻意不抛异常，由调用方负责记录日志：回调入口处的异常会被渠道当作失败，
     * 进而触发无休止重试。</p>
     *
     * @param value 待解析的字符串，可为 null
     * @return 匹配的状态；无法匹配时返回 null
     */
    public static PayStatus of(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        for (PayStatus status : values()) {
            if (status.name().equalsIgnoreCase(trimmed)) {
                return status;
            }
        }
        return null;
    }
}
