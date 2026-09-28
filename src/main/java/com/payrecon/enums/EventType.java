package com.payrecon.enums;

/**
 * 驱动支付单状态变化的事件类型。
 */
public enum EventType {

    /** 创建支付单。 */
    CREATE,

    /** 渠道回调。 */
    CALLBACK,

    /** 主动定时查询渠道。 */
    QUERY,

    /** 对账补偿。 */
    RECONCILE
}
