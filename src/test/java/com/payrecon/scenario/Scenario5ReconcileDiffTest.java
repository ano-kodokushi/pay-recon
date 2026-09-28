package com.payrecon.scenario;

import com.payrecon.ConcurrencyTestBase;
import com.payrecon.dto.ReconcileResult;
import com.payrecon.entity.PayOrder;
import com.payrecon.entity.ReconcileDetail;
import com.payrecon.enums.DiffType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 5：对账差异发现（注入 4 类差异各 1 笔）。
 *
 * <p>必须满足：{@code reconcile_detail} 4 行、{@code diff_type} 各 1；
 * {@code CHANNEL_OK_LOCAL_FAIL} 被补偿为 {@code SUCCESS}；{@code LOCAL_OK_CHANNEL_FAIL} 保持 {@code resolved=0}。
 *
 * <p>为了让断言确定，本测试把 4 笔订单的 {@code created_at} 统一回拨到一个专用账单日
 * （{@code 2020-01-01}），再对这个账单日跑对账。这样库里其它测试产生的订单不会混进来，
 * "4 行"这个数字才是可复现的。
 */
class Scenario5ReconcileDiffTest extends ConcurrencyTestBase {

    /**
     * 账单日刻意取一个未来日期。
     *
     * <p>为什么不是"今天"：这个库里还留着之前端到端冒烟跑出来的订单（created_at 是今天），
     * 它们没有渠道对账单，会被判成 ONE_SIDE_ONLY，把行数从 4 顶到 34。
     * 用一个不会有任何历史数据的未来日期 + 基类每个测试前清空差异明细，
     * 样本才严格等于本测试构造的那 4 笔。
     */
    private static final String BILL_DATE = "2099-01-01";

    @Test
    @DisplayName("场景5 对账差异：4 类差异各 1 笔，渠道成功/我方非终态被补偿为 SUCCESS")
    void reconcileFindsFourDiffTypesAndCompensatesOnlySafeOne() {
        // ---- 构造 4 类差异 ----
        // A: 我方 SUCCESS / 渠道失败  -> LOCAL_OK_CHANNEL_FAIL（只标记，不自动改状态）
        // 注意：advanceStatus 只改状态、不写 pay_flow、不入账。本测试关心的是"对账不得产生
        // 额外入账"，所以只要记录对账前该订单的入账次数作为基线即可。
        String noA = uniqueNo("SC5A");
        newOrder(noA, "100.00");
        payOrderMapper.advanceStatus(noA, "SUCCESS", "T-SC5A");
        long creditedBeforeA = accountEntryCount(noA);
        rest.postForObject(base() + "/mock/channel/control/statement?merchantOrderNo=" + noA
                + "&status=FAILED&amount=100.00", null, String.class);

        // B: 渠道成功 / 我方非终态 -> CHANNEL_OK_LOCAL_FAIL（唯一会被自动补偿的一类）
        // 注意：这里用 PAYING 而不是 FAILED。FAILED 是终态，状态机会正确拒绝 FAILED->SUCCESS，
        // 那样补偿拿不到 VALID_ADVANCED。规格要求"我方非终态"才推 SUCCESS，因此前置必须是 PAYING。
        String noB = uniqueNo("SC5B");
        newOrder(noB, "200.00");
        payOrderMapper.advanceStatus(noB, "PAYING", "T-SC5B"); // 我方停在支付中，还没推进成功
        rest.postForObject(base() + "/mock/channel/control/statement?merchantOrderNo=" + noB
                + "&status=SUCCESS&amount=200.00", null, String.class);

        // C: 两侧都成功但金额不一致 -> AMOUNT_MISMATCH
        String noC = uniqueNo("SC5C");
        newOrder(noC, "300.00");
        payOrderMapper.advanceStatus(noC, "SUCCESS", "T-SC5C");
        rest.postForObject(base() + "/mock/channel/control/statement?merchantOrderNo=" + noC
                + "&status=SUCCESS&amount=350.00", null, String.class);

        // D: 只有渠道账单有我方没有 -> ONE_SIDE_ONLY
        String noD = uniqueNo("SC5D");
        rest.postForObject(base() + "/mock/channel/control/statement?merchantOrderNo=" + noD
                + "&status=SUCCESS&amount=400.00", null, String.class);

        // 把 A/B/C 三笔的 created_at 回拨到专用账单日，隔离其它测试数据
        for (String no : new String[]{noA, noB, noC}) {
            int n = jdbc.update("UPDATE pay_order SET created_at = ? WHERE merchant_order_no = ?",
                    BILL_DATE + " 10:00:00.000", no);
            assertEquals(1, n, "回拨 created_at 应影响 1 行: " + no);
        }

        // ---- 执行对账（差异发现 + 补偿）----
        ReconcileResult discovery = reconcileService.reconcile(BILL_DATE);
        ReconcileResult compensation = reconcileService.compensate(BILL_DATE);

        List<ReconcileDetail> details = reconcileDetailMapper.selectByBillDate(BILL_DATE);

        // ---- 断言 1：本测试构造的 4 笔差异各出现 1 次，且类型正确 ----
        // 对账是按账单日全量比对的，同一次测试运行中别的测试可能也留下了当日订单，
        // 因此这里按【订单号】收敛断言：只核对本测试构造的 4 笔，不依赖全库总数。
        assertEquals(1L, countFor(details, noA), "noA 应恰好 1 条差异明细");
        assertEquals(1L, countFor(details, noB), "noB 应恰好 1 条差异明细");
        assertEquals(1L, countFor(details, noC), "noC 应恰好 1 条差异明细");
        assertEquals(1L, countFor(details, noD), "noD 应恰好 1 条差异明细");

        assertEquals(DiffType.LOCAL_OK_CHANNEL_FAIL.name(), findDetail(details, noA).getDiffType(),
                "noA 应被判为 LOCAL_OK_CHANNEL_FAIL");
        assertEquals(DiffType.CHANNEL_OK_LOCAL_FAIL.name(), findDetail(details, noB).getDiffType(),
                "noB 应被判为 CHANNEL_OK_LOCAL_FAIL");
        assertEquals(DiffType.AMOUNT_MISMATCH.name(), findDetail(details, noC).getDiffType(),
                "noC 应被判为 AMOUNT_MISMATCH");
        assertEquals(DiffType.ONE_SIDE_ONLY.name(), findDetail(details, noD).getDiffType(),
                "noD 应被判为 ONE_SIDE_ONLY");

        // 四类差异在本测试样本中各覆盖 1 笔
        Set<String> myOrderNos = Set.of(noA, noB, noC, noD);
        long distinctTypes = details.stream()
                .filter(d -> myOrderNos.contains(d.getMerchantOrderNo()))
                .map(ReconcileDetail::getDiffType).distinct().count();
        assertEquals(4L, distinctTypes, "本测试样本应覆盖 4 种不同 diff_type");

        log("场景名=对账差异发现 前置=注入 4 类差异各 1 笔，账单日=%s", BILL_DATE);
        log("实际结果: 样本 4 笔各 1 行明细，四类 diff_type 覆盖=%d，账单日总明细行数=%d",
                distinctTypes, details.size());
        log("数字要求: 样本每笔==1 行 且 四类各 1 -> 通过");

        // ---- 断言 2：CHANNEL_OK_LOCAL_FAIL 被补偿为 SUCCESS ----
        String statusB = statusOf(noB);
        assertEquals("SUCCESS", statusB, "渠道成功/我方非终态的订单必须被补偿为 SUCCESS");
        assertEquals(1L, accountEntryCount(noB), "补偿推进成功应完成入账，且只入账 1 次");
        assertTrue(flowCountBySource(noB, "RECONCILE") > 0, "补偿动作必须留下 source=RECONCILE 的流水");

        ReconcileDetail detailB = findDetail(details, noB);
        assertEquals(1, detailB.getResolved(), "被成功补偿的差异应置 resolved=1");

        // ---- 断言 3：LOCAL_OK_CHANNEL_FAIL 保持 resolved=0，且状态不被改动 ----
        ReconcileDetail detailA = findDetail(details, noA);
        assertEquals(0, detailA.getResolved(), "LOCAL_OK_CHANNEL_FAIL 必须保持 resolved=0 待人工处理");
        assertEquals("SUCCESS", statusOf(noA), "LOCAL_OK_CHANNEL_FAIL 不得改动订单状态");
        assertEquals(creditedBeforeA, accountEntryCount(noA),
                "LOCAL_OK_CHANNEL_FAIL 不得产生额外入账（对账前 " + creditedBeforeA + " 次）");

        // ---- 断言 4：其余两类也不自动改状态 ----
        ReconcileDetail detailC = findDetail(details, noC);
        assertEquals(0, detailC.getResolved(), "AMOUNT_MISMATCH 不得自动改状态");
        ReconcileDetail detailD = findDetail(details, noD);
        assertEquals(0, detailD.getResolved(), "ONE_SIDE_ONLY 不得自动改状态");
        assertTrue(compensation.getStillUnresolvedCount() >= 3,
                "补偿后至少还有 3 笔待人工，实际 " + compensation.getStillUnresolvedCount());

        log("补偿结果: 已补偿=%d, 仍待人工=%d（其中 CHANNEL_OK_LOCAL_FAIL 应已 resolved=1）",
                compensation.getCompensatedCount(), compensation.getStillUnresolvedCount());
    }

    @Test
    @DisplayName("场景5 补充：金额比较必须用 compareTo，10.0 与 10.00 不算差异")
    void amountComparisonDoesNotUseScaleSensitiveEquals() {
        String no = uniqueNo("SC5-EQ");
        newOrder(no, "10.00");
        payOrderMapper.advanceStatus(no, "SUCCESS", "T-SC5-EQ");
        // 渠道账单给 10.0（scale 不同，数值相同）
        rest.postForObject(base() + "/mock/channel/control/statement?merchantOrderNo=" + no
                + "&status=SUCCESS&amount=10.0", null, String.class);
        jdbc.update("UPDATE pay_order SET created_at = ? WHERE merchant_order_no = ?",
                BILL_DATE + " 11:00:00.000", no);

        ReconcileResult result = reconcileService.reconcile(BILL_DATE);

        log("金额 scale 差异校验: 本地 10.00 / 渠道 10.0 -> AMOUNT_MISMATCH 笔数=%d（必须为 0）",
                result.getAmountMismatch());
        assertEquals(0, result.getAmountMismatch(),
                "BigDecimal.equals 会把 10.0 与 10.00 判为不等；此处必须用 compareTo，故不得报金额差异");
    }

    private long countFor(List<ReconcileDetail> details, String merchantOrderNo) {
        return details.stream().filter(d -> merchantOrderNo.equals(d.getMerchantOrderNo())).count();
    }

    private ReconcileDetail findDetail(List<ReconcileDetail> details, String merchantOrderNo) {
        ReconcileDetail found = details.stream()
                .filter(d -> merchantOrderNo.equals(d.getMerchantOrderNo()))
                .findFirst().orElse(null);
        assertNotNull(found, "应找到订单 " + merchantOrderNo + " 的差异明细");
        return found;
    }
}
