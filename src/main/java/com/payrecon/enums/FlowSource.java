package com.payrecon.enums;

/**
 * 状态流转记录的来源。
 */
public enum FlowSource {

    /** 渠道回调。 */
    CALLBACK,

    /** 主动定时查询。 */
    QUERY,

    /** 对账补偿。 */
    RECONCILE
}
