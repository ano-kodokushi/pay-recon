package com.payrecon.enums;

/**
 * 对账差异类型。
 */
public enum DiffType {

    /** 本地成功、渠道失败。 */
    LOCAL_OK_CHANNEL_FAIL,

    /** 渠道成功、本地失败。 */
    CHANNEL_OK_LOCAL_FAIL,

    /** 金额不一致。 */
    AMOUNT_MISMATCH,

    /** 单边账（仅一方存在）。 */
    ONE_SIDE_ONLY
}
