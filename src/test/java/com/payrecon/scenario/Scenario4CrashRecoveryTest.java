package com.payrecon.scenario;

import com.payrecon.ConcurrencyTestBase;
import com.payrecon.enums.EventType;
import com.payrecon.enums.FlowSource;
import com.payrecon.enums.PayStatus;
import com.payrecon.service.AdvanceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 4：崩溃/异常恢复（回调丢失）。
 *
 * <p>必须满足：订单不永久停留在 {@code PAYING}；定时任务能在 N 分钟内捞回并推进。
 *
 * <p>这里用渠道 Mock 的"丢回调"开关模拟回调真的丢了（现实中最常见的故障），
 * 并直接把 {@code updated_at} 回拨到超时阈值之前，从而不必真的等一分钟。
 * 捞回动作由 {@code QueryJobService.runOnce()} 执行 —— 它就是定时任务调用的同一个方法。
 */
class Scenario4CrashRecoveryTest extends ConcurrencyTestBase {

    @Test
    @DisplayName("场景4 回调丢失恢复：PAYING 订单被主动查询捞回并推进为 SUCCESS")
    void stuckPayingOrderIsRecoveredByQueryJob() throws Exception {
        String no = uniqueNo("SC4");
        String amount = "777.00";
        String tradeNo = "MOCK-SC4-" + System.nanoTime();

        // 前置 1：建单并合法推进到 PAYING
        newOrder(no, amount);
        int toPaying = payOrderMapper.advanceStatus(no, "PAYING", tradeNo);
        assertEquals(1, toPaying, "前置：应能推进到 PAYING");
        assertEquals("PAYING", statusOf(no), "前置状态必须是 PAYING");

        // 前置 2：打开"丢回调"开关 —— 渠道的回调根本不会到达我方
        rest.postForObject(base() + "/mock/channel/control/drop-callback?drop=true", null, String.class);
        String notifyResult = notifyViaMock(no, tradeNo, amount, 1);
        log("丢回调开关已打开，回调发送结果=%s（sent 应为 0）", notifyResult);

        // 订单仍然停在 PAYING，且没有入账 —— 这正是"回调丢了"的真实后果
        assertEquals("PAYING", statusOf(no), "回调丢失后订单应仍停在 PAYING");
        assertEquals(0L, accountEntryCount(no), "回调丢失时不得入账");

        // 前置 3：把 updated_at 回拨 5 分钟，让订单满足"超过 N 分钟仍 PAYING"的条件
        // （测试配置的 staleMinutes=1，回拨 5 分钟足以命中；不必真的等一分钟）
        int backdated = jdbc.update(
                "UPDATE pay_order SET updated_at = NOW(3) - INTERVAL 5 MINUTE WHERE merchant_order_no = ?", no);
        assertEquals(1, backdated, "回拨 updated_at 应影响 1 行");

        // 前置 4：渠道查询接口回答 SUCCESS（渠道那边其实已经收款成功）
        rest.postForObject(base() + "/mock/channel/control/force-status?merchantOrderNo=" + no + "&status=SUCCESS",
                null, String.class);

        // ---- 执行兜底：定时任务的核心方法 ----
        int advanced = queryJobService.runOnce();

        String finalStatus = statusOf(no);
        long credited = accountEntryCount(no);
        long querySourceRows = flowCountBySource(no, "QUERY");
        long advanceFlows = advanceFlowCount(no);

        log("场景名=回调丢失恢复 并发数=1（单次兜底扫描） 前置=PAYING 且回调被丢弃、updated_at 回拨 5 分钟");
        log("实际结果: status=%s, 本次推进订单数=%d, 入账次数=%d, QUERY 流水=%d, 推进流水=%d",
                finalStatus, advanced, credited, querySourceRows, advanceFlows);
        log("数字要求: 不再停在 PAYING 且 状态==SUCCESS、入账==1、来源为 QUERY -> %s",
                ("SUCCESS".equals(finalStatus) && credited == 1 && querySourceRows > 0) ? "通过" : "不通过");

        // 订单不再永久停留在 PAYING
        assertEquals("SUCCESS", finalStatus, "兜底任务必须把卡在 PAYING 的订单捞回并推进为 SUCCESS");
        assertEquals(1L, credited, "捞回后入账必须恰好 1 次");
        assertTrue(querySourceRows > 0, "推进必须留下 source=QUERY 的流水，证明是主动查询而非回调做的");
        // 本场景只有 1 次真实跃迁：兜底任务把 PAYING 推进为 SUCCESS。
        // 前置的 CREATED->PAYING 是用 advanceStatus 直接改状态完成的，不写 pay_flow，
        // 所以这里统计到的跃迁流水只有兜底这一次。
        assertEquals(1L, advanceFlows, "应只有 1 次真实跃迁：兜底 PAYING->SUCCESS");
        // 只有兜底那一次该产生 QUERY 口径的推进流水
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_flow f JOIN pay_order o ON f.order_id = o.id "
                        + "WHERE o.merchant_order_no = ? AND f.to_status = 'SUCCESS' AND f.source = 'QUERY'",
                Long.class, no), "兜底推进应恰好留下 1 条 source=QUERY 的成功推进流水");

        // 收尾：复位丢回调开关，避免影响同 JVM 内的其它测试
        rest.postForObject(base() + "/mock/channel/control/drop-callback?drop=false", null, String.class);
    }

    @Test
    @DisplayName("场景4 补充：对终态订单不再发起推进（查询兜底只扫 PAYING）")
    void terminalOrderIsNotTouchedByQueryJob() {
        String no = uniqueNo("SC4-T");
        String amount = "20.00";
        newOrder(no, amount);

        // 走真实推进路径达到终态 SUCCESS，并真实完成一次入账
        // （advanceStatus 只改状态不入账；入账必须经 AdvanceService）
        AdvanceService.AdvanceResult r = advanceService.advance(no, PayStatus.SUCCESS,
                EventType.CALLBACK, FlowSource.CALLBACK, "{\"test\":\"terminal\"}", "T-SC4-T");
        assertEquals(AdvanceService.Outcome.VALID_ADVANCED, r.outcome(), "前置：应推进为 SUCCESS");
        assertEquals("SUCCESS", statusOf(no));
        assertEquals(1L, accountEntryCount(no), "前置：真实推进应完成 1 次入账");

        // 即便渠道查询说别的状态，终态订单也不该被扫描到（selectStalePaying 只选 PAYING）
        rest.postForObject(base() + "/mock/channel/control/force-status?merchantOrderNo=" + no + "&status=FAILED",
                null, String.class);
        queryJobService.runOnce();

        log("终态订单在兜底扫描后状态=%s（应保持 SUCCESS）", statusOf(no));
        assertEquals("SUCCESS", statusOf(no), "终态订单不得被兜底任务改动");
        assertEquals(1L, accountEntryCount(no), "终态订单入账次数不得变化");
    }
}
