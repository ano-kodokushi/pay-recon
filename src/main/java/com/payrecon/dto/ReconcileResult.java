package com.payrecon.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 对账结果：按差异类型分组的计数 + 明细订单号清单。
 *
 * <p>{@link #total} 恒等于四个分类计数之和，测试可以直接用「总数 = 各类之和」做交叉校验。</p>
 *
 * <p>{@code reconcile(...)} 填充差异发现部分（{@link #total} 及各分类计数与 {@link #orderNos}）；
 * {@code compensate(...)} 在此基础上填充补偿部分（{@link #compensatedCount} 与
 * {@link #stillUnresolvedCount}），因此 {@code /api/reconcile/run} 一次返回的是两者合并后的结果。</p>
 */
@Data
public class ReconcileResult {

    /** 账单日，原样回显请求中的 {@code yyyy-MM-dd} 字符串。 */
    private String billDate;

    /** 差异总数（四类之和）。 */
    private int total;

    /** 本地成功、渠道失败的条数（只标记差异，不自动改状态）。 */
    private int localOkChannelFail;

    /** 渠道成功、本地未成功的条数（唯一会被自动补偿推进的一类）。 */
    private int channelOkLocalFail;

    /** 金额不一致的条数（只标记差异，不自动改状态）。 */
    private int amountMismatch;

    /** 单边账的条数（我方有渠道无 / 渠道有我方无，只标记差异，不自动改状态）。 */
    private int oneSideOnly;

    /** 本次补偿中被标记为已解决（{@code resolved = 1}）的明细数。 */
    private int compensatedCount;

    /** 补偿结束后仍然 {@code resolved = 0}、需要人工介入的明细数。 */
    private int stillUnresolvedCount;

    /** 发现差异的订单号清单（按差异类型分组的全部订单号）。 */
    private List<String> orderNos = new ArrayList<>();

    /**
     * 构造一个空的、全部计数为 0 的结果，用于「当日无差异」等正常路径。
     *
     * @param billDate 账单日字符串
     * @return 计数全为 0 的结果对象
     */
    public static ReconcileResult empty(String billDate) {
        ReconcileResult result = new ReconcileResult();
        result.setBillDate(billDate);
        result.setOrderNos(new ArrayList<>());
        return result;
    }
}
