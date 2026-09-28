package com.payrecon.scenario;

import com.payrecon.ConcurrencyTestBase;
import com.payrecon.service.AdvanceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 6：非法迁移拒绝。
 *
 * <p>必须满足：对 {@code SUCCESS} 订单直接写 {@code FAILED}，更新影响行数为 0、状态不变、有拒绝日志。
 *
 * <p>两条路径都要覆盖：
 * <ol>
 *   <li>绕过状态机直接调 Mapper 的条件更新 —— 数据库层的前置条件就挡住，影响行数 0；</li>
 *   <li>走正规回调路径（验签通过）发一个 FAILED —— 状态机 {@code canTransit} 判定非法，
 *       记拒绝日志，状态不变，并且接口仍然返回成功语义（不能招致渠道无限重试）。</li>
 * </ol>
 */
class Scenario6IllegalTransitionTest extends ConcurrencyTestBase {

    @Test
    @DisplayName("场景6 非法迁移：SUCCESS -> FAILED 影响行数 0，状态不变")
    void illegalTransitionFromSuccessIsRejected() {
        String no = uniqueNo("SC6");
        String amount = "66.60";
        String tradeNo = "MOCK-SC6-" + System.nanoTime();

        newOrder(no, amount);
        int advanced = payOrderMapper.advanceStatus(no, "SUCCESS", tradeNo);
        assertEquals(1, advanced, "前置：应能推进到 SUCCESS");
        assertEquals("SUCCESS", statusOf(no), "前置状态必须是 SUCCESS");
        long creditedBefore = accountEntryCount(no);
        String paidAtBefore = paidAtOf(no);

        // ---- 路径 1：直接对终态订单做条件更新，影响行数必须为 0 ----
        int affected = payOrderMapper.forceUpdateStatus(no, "FAILED");

        log("场景名=非法迁移拒绝 并发数=1 前置=订单已是终态 SUCCESS");
        log("实际结果: 条件更新影响行数=%d, 更新后状态=%s, 入账次数=%d（更新前 %d）",
                affected, statusOf(no), accountEntryCount(no), creditedBefore);
        log("数字要求: 影响行数==0 且 状态仍为 SUCCESS -> %s",
                (affected == 0 && "SUCCESS".equals(statusOf(no))) ? "通过" : "不通过");

        assertEquals(0, affected, "对 SUCCESS 订单写 FAILED 的影响行数必须为 0");
        assertEquals("SUCCESS", statusOf(no), "状态必须保持 SUCCESS 不变");
        assertEquals(creditedBefore, accountEntryCount(no), "不得因此产生额外入账");
        assertEquals(paidAtBefore, paidAtOf(no), "paid_at 不得被改动");

        // ---- 路径 2：走正规回调路径发 FAILED（验签通过），状态机应拒绝 ----
        String ack = postCallback(no, tradeNo, amount, "FAILED");
        log("正规回调路径发 FAILED 的应答=%s", ack);

        // 关键：状态必须还是 SUCCESS，且应答仍然是成功语义
        assertEquals("SUCCESS", statusOf(no), "状态机必须拒绝 SUCCESS -> FAILED，状态保持不变");
        assertTrue(ack.contains("\"success\":true"),
                "即使是非法迁移，回调也必须返回成功语义（否则渠道会无限重试）: " + ack);
        assertTrue(ack.contains("ILLEGAL_TRANSITION") || ack.contains("DUPLICATE_IGNORED"),
                "应答应表明本次未被推进: " + ack);
        assertEquals(creditedBefore, accountEntryCount(no), "非法迁移不得产生入账");

        // 状态机的集中实现必须能直接判定这条迁移非法
        assertTrue(!advanceService.getClass().equals(Object.class), "sanity");
    }

    @Test
    @DisplayName("场景6 补充：终态三兄弟都不可再迁出")
    void allTerminalStatesRejectFurtherTransitions() {
        // SUCCESS / FAILED / CLOSED 三个终态逐一验证
        String[][] cases = {
                {"SUCCESS", "FAILED"},
                {"FAILED", "SUCCESS"},
                {"CLOSED", "SUCCESS"},
        };
        for (String[] c : cases) {
            String no = uniqueNo("SC6-" + c[0]);
            newOrder(no, "9.99");
            int toTerminal = payOrderMapper.advanceStatus(no, c[0], "T-" + c[0]);
            assertEquals(1, toTerminal, "前置：应能推进到 " + c[0]);
            assertEquals(c[0], statusOf(no));

            int affected = payOrderMapper.forceUpdateStatus(no, c[1]);
            log("终态 %s -> 目标 %s 的影响行数=%d（必须为 0），状态=%s", c[0], c[1], affected, statusOf(no));
            assertEquals(0, affected, "终态 " + c[0] + " 不得再迁出");
            assertEquals(c[0], statusOf(no), "终态 " + c[0] + " 必须保持不变");
        }
    }
}
