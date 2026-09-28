package com.payrecon.scenario;

import com.payrecon.ConcurrencyTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 1：并发下单（10 线程，同一 merchant_order_no）。
 *
 * <p>必须满足：{@code pay_order} 中该订单号恰好 1 行；10 次返回的 {@code order_id} 完全一致。
 *
 * <p>这一条是本项目最高优先级的验收：幂等如果靠应用层"先查后插"，并发下两个线程都会查到 null，
 * 然后各插一笔，这里就会看到 2 行、两个不同的 order_id。因此本测试同时验证了
 * 「幂等靠数据库唯一索引 + 精确捕获 DuplicateKeyException」这一设计确实成立。
 */
class Scenario1OrderIdempotencyTest extends ConcurrencyTestBase {

    private static final int THREADS = 10;

    @Test
    @DisplayName("场景1 并发下单：唯一索引保证只落 1 行，10 次返回同一 order_id")
    void concurrentCreateOrderIsIdempotent() throws Exception {
        String no = uniqueNo("SC1");
        String amount = "199.99";

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(THREADS);
        ConcurrentLinkedQueue<String> responses = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    responses.add(createOrder(no, amount));
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

        assertTrue(finished, "并发下单未在 60 秒内全部完成");
        assertTrue(errors.isEmpty(), "并发下单出现异常: " + errors);
        assertEquals(THREADS, responses.size(), "应有 " + THREADS + " 个响应");

        // ---- 断言 1：pay_order 恰好 1 行 ----
        long rows = orderRows(no);

        // ---- 断言 2：10 次返回的 orderId 完全一致 ----
        List<Long> orderIds = new ArrayList<>();
        int reusedCount = 0;
        for (String body : responses) {
            Long id = extractLong(body, "orderId");
            orderIds.add(id);
            if (body.contains("\"reused\":true")) {
                reusedCount++;
            }
        }
        long distinctIds = orderIds.stream().distinct().count();

        log("场景名=并发下单 并发数=%d 前置=同一 merchant_order_no 且 pay_order 无该单", THREADS);
        log("实际结果: pay_order 行数=%d, 不同 orderId 个数=%d, 返回 reused=true 的次数=%d",
                rows, distinctIds, reusedCount);
        log("数字要求: 行数==1 且 不同 orderId 个数==1 -> %s",
                (rows == 1 && distinctIds == 1) ? "通过" : "不通过");

        assertEquals(1L, rows, "pay_order 中该订单号必须恰好 1 行（并发下多行说明幂等失效）");
        assertEquals(1L, distinctIds, "10 次返回的 order_id 必须完全一致");

        // ---- 附带：CREATE 流水恰好 1 条（只有抢到插入的那一次写）----
        long createFlow = flowCount(no, "CREATE");
        assertEquals(1L, createFlow, "event_type=CREATE 的 pay_flow 必须恰好 1 条");

        // 其余 9 次都应是复用已存在订单
        assertEquals(THREADS - 1, reusedCount, "恰好 1 次真正插入，其余 9 次应复用已存在订单");
        log("附加校验: CREATE 流水=%d 条, reused=true 次数=%d", createFlow, reusedCount);
    }

    /** 从 JSON 里粗暴取一个 long 字段，避免为此引入额外依赖。 */
    private Long extractLong(String json, String field) {
        String key = "\"" + field + "\":";
        int i = json.indexOf(key);
        assertTrue(i >= 0, "响应里找不到字段 " + field + ": " + json);
        int start = i + key.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }
}
