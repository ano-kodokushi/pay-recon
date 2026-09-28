package com.payrecon.scenario;

import com.payrecon.ConcurrencyTestBase;
import com.payrecon.entity.PayOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 2：重复回调（同一报文重放 5 次，含并发重放）。
 *
 * <p>必须满足：{@code status} 只成功 1 次；{@code paid_at} 不被覆盖（仍是第 1 次的值）；
 * 入账动作只执行 1 次；{@code pay_flow} 有 1 条推进 + 4 条"重复被忽略"。
 *
 * <p>重放通过渠道 Mock 的 {@code times} 参数发出，走的是完整端到端链路（含 HMAC 验签），
 * 而不是直接调 service —— 这样才能证明真接口在重复回调下也返回成功语义且不重复入账。
 */
class Scenario2DuplicateCallbackTest extends ConcurrencyTestBase {

    private static final int REPLAYS = 5;

    @Test
    @DisplayName("场景2 重复回调：推进 1 次、入账 1 次、paid_at 不被覆盖、4 条重复被忽略")
    void duplicateCallbackIsIdempotent() throws Exception {
        String no = uniqueNo("SC2");
        String amount = "58.80";
        String tradeNo = "MOCK-SC2-" + System.nanoTime();

        // 前置：订单存在且为 CREATED
        PayOrder order = newOrder(no, amount);
        assertNotNull(order, "前置订单必须创建成功");
        assertEquals("CREATED", statusOf(no), "前置状态必须是 CREATED");

        // 第一次回调：应当推进为 SUCCESS 并完成入账
        String firstAck = postCallback(no, tradeNo, amount, "SUCCESS");
        log("首次回调应答=%s", firstAck);
        assertEquals("SUCCESS", statusOf(no), "首次回调后状态应为 SUCCESS");
        String paidAtAfterFirst = paidAtOf(no);
        assertNotNull(paidAtAfterFirst, "首次推进必须写入 paid_at");
        long creditedAfterFirst = accountEntryCount(no);
        assertEquals(1L, creditedAfterFirst, "首次回调后入账必须恰好 1 次");

        // 基线：重放之前已经发生的跃迁数与被忽略数
        long advanceBefore = advanceFlowCount(no);
        long ignoredBefore = ignoredFlowCount(no);
        long rawPayloadBefore = rawPayloadFlowCount(no);

        // 重放 5 次（并发发送，端到端走验签链路）
        String notifyResult = notifyViaMock(no, tradeNo, amount, REPLAYS);
        log("重放 %d 次发送结果=%s", REPLAYS, notifyResult);

        // 等一下让所有回调线程处理完
        TimeUnit.MILLISECONDS.sleep(1500);

        // ---- 断言区 ----
        String finalStatus = statusOf(no);
        String paidAtAfterReplay = paidAtOf(no);
        long credited = accountEntryCount(no);
        long advanceFlowsAfter = advanceFlowCount(no);
        long newAdvances = advanceFlowsAfter - advanceBefore;
        long ignoredFlows = ignoredFlowCount(no);
        long callbackRawRows = rawPayloadFlowCount(no);

        log("场景名=重复回调 并发数=%d（重放）+1（首次） 前置=订单已 CREATED 且已成功推进过一次", REPLAYS);
        log("实际结果: status=%s, paid_at 首次=%s 重放后=%s, 入账次数=%d, 重放新增跃迁=%d, 重复被忽略流水=%d, 原始报文行=%d",
                finalStatus, paidAtAfterFirst, paidAtAfterReplay, credited, newAdvances, ignoredFlows, callbackRawRows);
        log("数字要求: 重放新增跃迁==0, 入账==1, paid_at 不变, 重复被忽略==%d -> %s",
                6 - 1,
                (newAdvances == 0 && credited == 1 && ignoredFlows == 6 - 1
                        && paidAtAfterFirst.equals(paidAtAfterReplay)) ? "通过" : "不通过");

        // 诊断输出：把每一条流水原样打出来，便于核对"1 条真正的跃迁 + 5 条重复被忽略"的口径。
        jdbc.queryForList(
                "SELECT f.id, f.event_type, f.source, f.from_status, f.to_status, "
                        + "CASE WHEN f.raw_payload IS NULL THEN 'NULL' ELSE 'PAYLOAD' END AS kind "
                        + "FROM pay_flow f JOIN pay_order o ON f.order_id = o.id "
                        + "WHERE o.merchant_order_no = ? ORDER BY f.id", no)
                .forEach(row -> log("  flow#%s event=%s source=%s from=%s to=%s kind=%s",
                        row.get("id"), row.get("event_type"), row.get("source"),
                        row.get("from_status"), row.get("to_status"), row.get("kind")));

        // status 只成功 1 次
        assertEquals("SUCCESS", finalStatus, "订单状态应为 SUCCESS");
        // 入账动作只执行 1 次（硬断言：account_entry 的行数）
        assertEquals(1L, credited, "入账动作必须只执行 1 次（重复回调不得重复入账）");
        // paid_at 不被覆盖
        assertEquals(paidAtAfterFirst, paidAtAfterReplay, "paid_at 必须保持第 1 次的值，不得被后续回调覆盖");
        // 重放不产生任何新的状态跃迁
        assertEquals(0L, newAdvances, "重放的 " + REPLAYS + " 次回调不得产生任何新的状态跃迁");
        // pay_flow：整条生命周期内只应有 1 条真正的跃迁 + 4 条"重复被忽略"。
        // 口径必须是 from != to：重复行记录的是 SUCCESS->SUCCESS，同样带 to_status='SUCCESS'，
        // 直接按 to_status 统计会把没有发生的迁移也算成跃迁。
        assertEquals(1L, advanceFlowCount(no), "整条生命周期内只应有 1 条真正的状态跃迁");
        // 重放本身必须新增恰好 4 条"重复被忽略"（用增量而不是总数：第一次回调有可能在自己的
        // 读快照里就已经看到 SUCCESS（例如与提交竞争），那时它也会留下一条被忽略记录，
        // 那是合法的实现差异，不该影响"重放 5 次"这件事的断言）。
        assertTrue(ignoredFlows - ignoredBefore >= REPLAYS - 1,
                "重放的 " + REPLAYS + " 次应新增至少 " + (REPLAYS - 1) + " 条'重复被忽略'流水，实际新增 "
                        + (ignoredFlows - ignoredBefore));
        // 更强的确定性不变量：总共 6 次回调（1 首次 + 5 重放），其中只有 1 次造成了状态跃迁，
        // 因此其余 5 次都必须以"重复被忽略"落痕——不多不少。
        assertEquals(6L - 1L, ignoredFlowCount(no),
                "6 次回调中只有 1 次真正跃迁，其余 5 次必须各留一条'重复被忽略'流水");
        // 首次 + 5 次重放 = 6 次回调，每次都必须无条件先落一行原始报文
        assertEquals(6L, callbackRawRows, "每次回调都必须落一行原始报文（1 首次 + 5 重放）");
        assertEquals((long) REPLAYS, callbackRawRows - rawPayloadBefore,
                "重放的 " + REPLAYS + " 次应新增恰好 " + REPLAYS + " 行原始报文");

        log("附加校验: 回调总次数=6, 原始报文行=%d（规格要求先落原文，故每次都有）", callbackRawRows);
    }

    @Test
    @DisplayName("场景2 补充：重复回调时接口必须返回成功语义")
    void repeatCallbackStillReturnsSuccess() {
        String no = uniqueNo("SC2-ACK");
        String amount = "10.00";
        newOrder(no, amount);

        postCallback(no, "T-ACK", amount, "SUCCESS");
        String secondAck = postCallback(no, "T-ACK", amount, "SUCCESS");
        String thirdAck = postCallback(no, "T-ACK", amount, "SUCCESS");

        log("第二次应答=%s", secondAck);
        log("第三次应答=%s", thirdAck);

        // 口径必须用 advanceFlowCount（from != to 才算跃迁）：
        // 重复回调留下的 SUCCESS->SUCCESS 行同样带 to_status='SUCCESS'，
        // 直接按 to_status 统计会把"没发生的迁移"也算进来。
        assertEquals(1L, advanceFlowCount(no), "整条生命周期内只应有 1 条真正跃迁到 SUCCESS 的流水");
        assertTrue(ignoredFlowCount(no) >= 2,
                "第二、三次回调都应留下'重复被忽略'的流水，实际 " + ignoredFlowCount(no));

        // 规格 §0.5：回调失败会招致渠道无限重试，因此重复回调必须返回成功语义
        assertTrue(secondAck.contains("\"success\":true"), "重复回调必须返回成功语义: " + secondAck);
        assertTrue(thirdAck.contains("\"success\":true"), "重复回调必须返回成功语义: " + thirdAck);

        // 重复回调不被推进即可。注意有两种同样正确的形态：
        //  - ILLEGAL_TRANSITION：订单已是终态 SUCCESS，状态机正确拒绝 SUCCESS -> SUCCESS 这条迁移；
        //  - DUPLICATE_IGNORED：条件更新影响行数 0，被识别为重复回调。
        // 两者都必须 credited=false，且入账次数不得增加。
        assertTrue(secondAck.contains("\"credited\":false"), "重复回调不得入账: " + secondAck);
        assertTrue(secondAck.contains("ILLEGAL_TRANSITION") || secondAck.contains("DUPLICATE_IGNORED"),
                "重复回调应被识别为未推进: " + secondAck);
        assertEquals(1L, accountEntryCount(no), "重复回调后入账仍只能是 1 次");
    }
}
