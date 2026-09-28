package com.payrecon;

import com.payrecon.entity.PayOrder;
import com.payrecon.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并发测试基类：真实 MySQL + 真实 HTTP 服务器。
 *
 * <p>规格 §5 的硬性要求是「使用真实 MySQL + 真实线程池，禁止用 Mock 替代数据库」。
 * 因此这里用 {@code DEFINED_PORT} 起真实 Tomcat（端口固定 8080，与渠道 Mock 配置的回调地址一致），
 * 测试通过真实 HTTP 打自己的接口，走完整链路（含验签）。
 *
 * <p>{@code app.query-job.enabled=false} 关掉定时调度：否则后台任务会在断言过程中
 * 改动 PAYING 订单，让并发断言变得不确定。场景 4 需要验证兜底能力时，
 * 测试自己直接调用 {@code QueryJobService.runOnce()}，是确定性的。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@TestPropertySource(properties = {
        "app.query-job.enabled=false",
        "server.port=8080"
})
public abstract class ConcurrencyTestBase {

    protected static final AtomicInteger SEQ = new AtomicInteger(0);

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected OrderService orderService;

    @Autowired
    protected com.payrecon.mapper.PayOrderMapper payOrderMapper;

    @Autowired
    protected com.payrecon.mapper.ReconcileDetailMapper reconcileDetailMapper;

    @Autowired
    protected com.payrecon.mock.ChannelBillStore channelBillStore;

    @Autowired
    protected com.payrecon.service.QueryJobService queryJobService;

    @Autowired
    protected com.payrecon.service.AdvanceService advanceService;

    @Autowired
    protected com.payrecon.service.ReconcileService reconcileService;

    @Autowired
    protected com.payrecon.service.HmacVerifier hmacVerifier;

    protected String base() {
        return "http://127.0.0.1:8080";
    }

    /** 本测试自己创建的订单号，供结束后精确清理（绝不做全表删除——那会误伤别的测试）。 */
    protected final java.util.List<String> createdOrderNos = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void baseSetUp() {
        // 渠道 Mock 的内存状态（账单/强制状态/丢回调开关）是进程级共享的，必须逐测试清空。
        // 差异明细同理：它是按账单日全量比对的，旧行会把本次样本的计数顶高。
        // 注意：这里【不】做全表 DELETE。测试之间必须互不干扰，一个测试把它自己创建的订单
        // 清掉是可以的，但顺手删掉整张表会破坏其它测试的前置与断言。
        channelBillStore.clear();
        reconcileDetailMapper.delete(null);
        createdOrderNos.clear();
    }

    @AfterEach
    void baseTearDown() {
        // 只清理本测试创建的订单（含其流水与入账），保证下一次运行不受本次残留影响。
        // pay_flow 在 src/main 中只追加、永不改删；这里的删除是测试夹具清理，
        // 不改变「应用代码不修改/不删除流水」这条规格约束。
        if (createdOrderNos.isEmpty()) {
            return;
        }
        int removed = 0;
        for (String no : createdOrderNos) {
            jdbc.update("DELETE FROM account_entry WHERE order_id IN "
                    + "(SELECT id FROM pay_order WHERE merchant_order_no = ?)", no);
            jdbc.update("DELETE FROM pay_flow WHERE order_id IN "
                    + "(SELECT id FROM pay_order WHERE merchant_order_no = ?)", no);
            jdbc.update("DELETE FROM reconcile_detail WHERE merchant_order_no = ?", no);
            removed += jdbc.update("DELETE FROM pay_order WHERE merchant_order_no = ?", no);
        }
        if (removed > 0) {
            log("夹具清理：删除本测试订单 %d 行", removed);
        }
    }

    /** 生成全测试唯一的商户订单号。 */
    protected String uniqueNo(String prefix) {
        return prefix + "-" + System.currentTimeMillis() + "-" + SEQ.incrementAndGet();
    }

    protected HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** 调我方下单接口。 */
    protected String createOrder(String no, String amount) {
        String body = "{\"merchantOrderNo\":\"" + no + "\",\"amount\":" + amount + "}";
        // 登记订单号，供 @AfterEach 精确清理（场景 1 走 HTTP 下单，不经过 newOrder）。
        // 直接 add：10 线程会重复登记同一个单号，但清理时重复删除是幂等的（第二次影响 0 行），
        // 而"先 contains 再 add"反而是非原子的 check-then-act，更糟。
        createdOrderNos.add(no);
        return rest.postForObject(base() + "/api/order/create", new HttpEntity<>(body, jsonHeaders()), String.class);
    }

    /** 用真实下单服务建单并返回订单实体。 */
    protected PayOrder newOrder(String no, String amount) {
        com.payrecon.dto.OrderCreateRequest req = new com.payrecon.dto.OrderCreateRequest();
        req.setMerchantOrderNo(no);
        req.setAmount(new java.math.BigDecimal(amount));
        orderService.createOrder(req);
        // 登记，供 @AfterEach 精确清理本测试创建的数据
        createdOrderNos.add(no);
        return orderService.getOrderByNo(no);
    }

    /** 登记一个不是通过 newOrder 创建的订单号（例如仅出现在渠道对账单里的单边账）。 */
    protected void trackOrderNo(String no) {
        createdOrderNos.add(no);
    }

    /**
     * 直接调用真实回调接口，并对报文体做正确的 HMAC 签名。
     *
     * <p>验签是规格 §3 任务 2 的第 1 步且「禁止跳过」，因此测试必须像真实渠道那样签名；
     * 不签名会被回调接口拒绝（result=REJECTED），那是正确行为而不是缺陷。
     */
    protected String postCallback(String no, String tradeNo, String amount, String status) {
        String body = "{\"merchantOrderNo\":\"" + no + "\",\"tradeNo\":\"" + tradeNo
                + "\",\"amount\":" + amount + ",\"status\":\"" + status + "\"}";
        HttpHeaders headers = jsonHeaders();
        headers.set("X-Signature", hmacVerifier.sign(body));
        return rest.postForObject(base() + "/api/pay/callback", new HttpEntity<>(body, headers), String.class);
    }

    /** 不签名的回调，用于验证"验签失败也必须返回成功语义且不得推进状态"。 */
    protected String postUnsignedCallback(String no, String tradeNo, String amount, String status) {
        String body = "{\"merchantOrderNo\":\"" + no + "\",\"tradeNo\":\"" + tradeNo
                + "\",\"amount\":" + amount + ",\"status\":\"" + status + "\"}";
        return rest.postForObject(base() + "/api/pay/callback", new HttpEntity<>(body, jsonHeaders()), String.class);
    }

    /** 用渠道 Mock 发送回调（走完整验签链路的端到端方式）。 */
    protected String notifyViaMock(String no, String tradeNo, String amount, int times) {
        String body = "{\"merchantOrderNo\":\"" + no + "\",\"tradeNo\":\"" + tradeNo
                + "\",\"amount\":" + amount + ",\"times\":" + times + "}";
        return rest.postForObject(base() + "/mock/channel/notify", new HttpEntity<>(body, jsonHeaders()), String.class);
    }

    protected String statusOf(String no) {
        return jdbc.queryForObject("SELECT status FROM pay_order WHERE merchant_order_no = ?", String.class, no);
    }

    protected long orderRows(String no) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM pay_order WHERE merchant_order_no = ?", Long.class, no);
        return n == null ? 0L : n;
    }

    protected long accountEntryCount(String no) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_entry e JOIN pay_order o ON e.order_id = o.id "
                        + "WHERE o.merchant_order_no = ?", Long.class, no);
        return n == null ? 0L : n;
    }

    protected long flowCount(String no, String eventType) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_flow f JOIN pay_order o ON f.order_id = o.id "
                        + "WHERE o.merchant_order_no = ? AND f.event_type = ?", Long.class, no, eventType);
        return n == null ? 0L : n;
    }

    /**
     * 统计"真正发生了状态跃迁"的流水行数。
     *
     * <p>判据是 {@code from_status != to_status}（两者均非空）。
     * 不能只判 {@code to_status IS NOT NULL}：重复被忽略的那一行同样记录着目标状态，
     * 但它并没有发生迁移，把它算成跃迁会让"只推进一次"的断言失去意义。
     */
    protected long advanceFlowCount(String no) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_flow f JOIN pay_order o ON f.order_id = o.id "
                        + "WHERE o.merchant_order_no = ? AND f.from_status IS NOT NULL "
                        + "AND f.to_status IS NOT NULL AND f.from_status <> f.to_status", Long.class, no);
        return n == null ? 0L : n;
    }

    /**
     * 统计"原始报文落库"行数（{@code raw_payload} 非空）。
     *
     * <p>一次回调/查询/对账动作必然先写一行原始报文，且这一行是无条件写的
     * （规格 §0.4：必须先落 pay_flow 原始报文，再处理业务），因此重放 N 次就有 N 行。
     */
    protected long rawPayloadFlowCount(String no) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_flow f JOIN pay_order o ON f.order_id = o.id "
                        + "WHERE o.merchant_order_no = ? AND f.raw_payload IS NOT NULL", Long.class, no);
        return n == null ? 0L : n;
    }

    /**
     * 统计"重复回调/重复查询被忽略"的行数。
     *
     * <p>重复被忽略的落库形态是 {@code from_status = to_status}
     * （例如 SUCCESS -> SUCCESS，记录"当时已经是该状态、无需迁移"）：
     * 它没有原始报文（那一行已单独记录），也没有发生任何迁移。
     */
    protected long ignoredFlowCount(String no) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_flow f JOIN pay_order o ON f.order_id = o.id "
                        + "WHERE o.merchant_order_no = ? AND f.raw_payload IS NULL "
                        + "AND f.from_status IS NOT NULL AND f.to_status IS NOT NULL "
                        + "AND f.from_status = f.to_status", Long.class, no);
        return n == null ? 0L : n;
    }

    /** 统计某来源（CALLBACK / QUERY / RECONCILE）的流水行数。 */
    protected long flowCountBySource(String no, String source) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_flow f JOIN pay_order o ON f.order_id = o.id "
                        + "WHERE o.merchant_order_no = ? AND f.source = ?", Long.class, no, source);
        return n == null ? 0L : n;
    }

    protected String paidAtOf(String no) {
        return jdbc.queryForObject("SELECT paid_at FROM pay_order WHERE merchant_order_no = ?", String.class, no);
    }

    protected void log(String format, Object... args) {
        System.out.println("[场景验证] " + String.format(format, args));
    }
}
