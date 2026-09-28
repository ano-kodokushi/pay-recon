package com.payrecon.scenario;

import com.payrecon.ConcurrencyTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 3：回调与主动查询并发打同一笔。
 *
 * <p>必须满足：状态不倒退、不出现非法跃迁、入账仍然只 1 次。
 *
 * <p>前置把订单推进到 PAYING（合法迁移 CREATED -> PAYING），然后让「回调推进为 SUCCESS」与
 * 「主动查询兜底推进为 SUCCESS」同时起跑。两条路径都调用同一个
 * {@code AdvanceService.advance(...)}，因此只有抢到条件更新的那一次会入账。
 */
class Scenario3CallbackQueryRaceTest extends ConcurrencyTestBase {

    private static final int QUERY_THREADS = 4;

    @Test
    @DisplayName("场景3 回调与查询并发：不倒退、不非法跃迁、入账只 1 次")
    void callbackAndQueryRaceCreditsOnce() throws Exception {
        String no = uniqueNo("SC3");
        String amount = "321.00";
        String tradeNo = "MOCK-SC3-" + System.nanoTime();

        // 前置 1：建单
        newOrder(no, amount);

        // 前置 2：合法推进到 PAYING（此时还不该入账）
        int toPaying = payOrderMapper.advanceStatus(no, "PAYING", tradeNo);
        assertEquals(1, toPaying, "前置：CREATED -> PAYING 应成功，影响行数 1");
        assertEquals("PAYING", statusOf(no), "前置状态必须是 PAYING");
        assertEquals(0L, accountEntryCount(no), "前置：PAYING 阶段不应入账");

        // 前置 3：渠道侧对查询的回答强制为 SUCCESS，使查询路径也认为该推进
        rest.postForObject(base() + "/mock/channel/control/force-status?merchantOrderNo=" + no + "&status=SUCCESS",
                null, String.class);

        // 前置 4：把 updated_at 回拨，使订单落入"超时仍 PAYING"的扫描窗口。
        // 主动查询兜底只处理 updated_at 超过 staleMinutes 的 PAYING 订单；
        // 刚建的单还不算超时，不回拨的话查询线程会一条都扫不到（并发就名不副实）。
        int backdated = jdbc.update(
                "UPDATE pay_order SET updated_at = NOW(3) - INTERVAL 5 MINUTE WHERE merchant_order_no = ?", no);
        assertEquals(1, backdated, "回拨 updated_at 应影响 1 行");

        // 基线：并发之前已经发生的跃迁数（前置 CREATED->PAYING 占 1 条）
        long advanceBefore = advanceFlowCount(no);

        int totalThreads = QUERY_THREADS + 1; // 4 个查询路径 + 1 个回调路径
        ExecutorService pool = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(totalThreads);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        // 回调线程：走真实回调接口（含验签）
        pool.submit(() -> {
            try {
                startGate.await();
                postCallback(no, tradeNo, amount, "SUCCESS");
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                doneGate.countDown();
            }
        });

        // 查询线程：走定时任务的核心方法（与回调复用同一个 advance）
        for (int i = 0; i < QUERY_THREADS; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    queryJobService.runOnce();
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean finished = doneGate.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertTrue(finished, "并发未在 60 秒内完成");
        assertTrue(errors.isEmpty(), "并发过程出现异常: " + errors);

        TimeUnit.MILLISECONDS.sleep(500);

        // ---- 断言区 ----
        String finalStatus = statusOf(no);
        long credited = accountEntryCount(no);
        long advanceFlows = advanceFlowCount(no);
        long newAdvances = advanceFlows - advanceBefore;
        long querySourceRows = flowCountBySource(no, "QUERY");
        long callbackSourceRows = flowCountBySource(no, "CALLBACK");

        log("场景名=回调与查询并发 并发数=%d（1 回调 + %d 查询） 前置=订单 PAYING、渠道查询强制 SUCCESS",
                totalThreads, QUERY_THREADS);
        log("实际结果: 最终状态=%s, 入账次数=%d, 并发新增跃迁=%d, QUERY 流水行=%d, CALLBACK 流水行=%d",
                finalStatus, credited, newAdvances, querySourceRows, callbackSourceRows);
        log("数字要求: 状态==SUCCESS（不倒退）, 入账==1, 并发新增跃迁==1 -> %s",
                ("SUCCESS".equals(finalStatus) && credited == 1 && newAdvances == 1) ? "通过" : "不通过");

        // 状态不倒退：必须停在 SUCCESS 而不是回到 PAYING/CREATED
        assertEquals("SUCCESS", finalStatus, "并发推进后状态必须是 SUCCESS，不得倒退");
        // 入账仍只 1 次
        assertEquals(1L, credited, "回调与查询并发时入账必须仍然只 1 次");
        // 不出现非法跃迁：回调与查询同时打同一笔，只能有一次真正抢到 PAYING -> SUCCESS
        assertEquals(1L, newAdvances, "并发期间只能有 1 次真正的状态跃迁（其余都是重复被忽略）");
        // 查询路径确实参与了竞争（证明这是一个真实的并发场景，而不是查询线程啥也没干）
        assertTrue(querySourceRows >= QUERY_THREADS,
                "查询路径应至少写下 " + QUERY_THREADS + " 行 QUERY 流水，实际 " + querySourceRows);

        log("附加校验: QUERY 来源流水=%d 行（说明查询路径确实参与竞争）", querySourceRows);
    }
}
