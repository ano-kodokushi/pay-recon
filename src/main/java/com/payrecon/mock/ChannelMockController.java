package com.payrecon.mock;

import com.payrecon.mock.dto.ChannelNotifyRequest;
import com.payrecon.mock.dto.ChannelPayRequest;
import com.payrecon.mock.dto.ChannelPayResponse;
import com.payrecon.mock.dto.ChannelQueryRequest;
import com.payrecon.mock.dto.ChannelQueryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 渠道模拟器的 HTTP 端点。
 *
 * <p><b>这不是一个真实的渠道对接实现，而是为并发测试提供故障注入能力的 mock。</b>
 * 规格 §3 任务 5 明确要求：不接真实第三方，但必须可注入异常，否则 §5 的并发测试做不出来。
 *
 * <p>故障注入能力分布在两类端点：
 * <ul>
 *   <li>业务端点 {@code /mock/channel/pay|query|notify} —— 模拟渠道收款、查询、回调；</li>
 *   <li>控制端点 {@code /mock/channel/control/**} —— 测试用它打开"丢回调"开关、
 *       强制查询返回值、注入对账单差异行、清空状态。</li>
 * </ul>
 *
 * <p>本控制器只做协议转换，所有状态都放在 {@link ChannelBillStore}，出站回调委托给
 * {@link ChannelNotifier}。
 */
@Slf4j
@RestController
@RequestMapping("/mock/channel")
@RequiredArgsConstructor
public class ChannelMockController {

    private final ChannelBillStore store;
    private final ChannelNotifier channelNotifier;

    private final AtomicLong seq = new AtomicLong(0);

    // ------------------------------------------------------------------
    // 业务端点
    // ------------------------------------------------------------------

    /**
     * 模拟渠道收款成功。
     *
     * <p>渠道侧并不真的收钱，只是记一条自己的账单并返回流水号；
     * 我方是否推进状态完全取决于后续回调或主动查询。
     */
    @PostMapping("/pay")
    public ChannelPayResponse pay(@RequestBody ChannelPayRequest req) {
        String tradeNo = "MOCK" + System.currentTimeMillis() + "-" + seq.incrementAndGet();
        // 金额原样保存为 BigDecimal，绝不经过 double，避免精度丢失
        store.putBill(new ChannelBill(req.getMerchantOrderNo(), "SUCCESS", req.getAmount(), tradeNo));
        log.info("[渠道Mock] 收款记账 merchantOrderNo={}, amount={}, tradeNo={}",
                req.getMerchantOrderNo(), req.getAmount(), tradeNo);
        return new ChannelPayResponse(tradeNo, "SUCCESS", req.getMerchantOrderNo());
    }

    /**
     * 模拟渠道订单查询。
     *
     * <p>解析顺序：强制状态优先（测试注入），其次已记账的账单，最后视为渠道无此单。
     * 返回 {@code NOT_FOUND} 表示渠道侧不存在该订单，此时 {@code amount} 为 null。
     * 本端点恒返回 HTTP 200。
     */
    @PostMapping("/query")
    public ChannelQueryResponse query(@RequestBody ChannelQueryRequest req) {
        String no = req.getMerchantOrderNo();

        String forced = store.forcedStatus(no);
        if (forced != null) {
            if ("NOT_FOUND".equalsIgnoreCase(forced)) {
                return new ChannelQueryResponse(no, "NOT_FOUND", null, null);
            }
            ChannelBill bill = store.getBill(no);
            return new ChannelQueryResponse(no, forced,
                    bill == null ? null : bill.tradeNo(),
                    bill == null ? null : bill.amount());
        }

        ChannelBill bill = store.getBill(no);
        if (bill == null) {
            return new ChannelQueryResponse(no, "NOT_FOUND", null, null);
        }
        return new ChannelQueryResponse(no, bill.status(), bill.tradeNo(), bill.amount());
    }

    /**
     * 模拟渠道向我方推送回调。
     *
     * <p>{@code times} 支持重复发送同一笔报文——这是复现"重复回调"场景的关键能力。
     * 是否真的发出由 {@link ChannelBillStore#isDropCallback()} 决定（丢回调故障注入）。
     *
     * @return {@code {"sent": n}}，n 为实际发出的次数；丢回调开关打开时恒为 0
     */
    @PostMapping("/notify")
    public Map<String, Object> notify(@RequestBody ChannelNotifyRequest req) {
        int times = req.getTimes() == null ? 1 : req.getTimes();
        int sent = channelNotifier.notify(req.getMerchantOrderNo(), req.getTradeNo(), req.getAmount(), times);
        Map<String, Object> body = new HashMap<>();
        body.put("sent", sent);
        return body;
    }

    // ------------------------------------------------------------------
    // 控制端点（故障注入）
    // ------------------------------------------------------------------

    /**
     * 丢回调开关：打开后 {@code /notify} 不发送任何报文，用于模拟回调丢失，
     * 从而验证主动查询兜底能捞回订单。
     */
    @PostMapping("/control/drop-callback")
    public Map<String, Object> dropCallback(@RequestParam(defaultValue = "true") boolean drop) {
        store.setDropCallback(drop);
        Map<String, Object> body = new HashMap<>();
        body.put("dropCallback", store.isDropCallback());
        return body;
    }

    /**
     * 强制指定某订单的渠道查询状态。
     *
     * <p>字面量 {@code NOT_FOUND} 是合法取值（渠道无此单），因此这里不做枚举校验。
     */
    @PostMapping("/control/force-status")
    public Map<String, Object> forceStatus(@RequestParam String merchantOrderNo,
                                           @RequestParam String status) {
        store.forceStatus(merchantOrderNo, status);
        Map<String, Object> body = new HashMap<>();
        body.put("merchantOrderNo", merchantOrderNo);
        body.put("forcedStatus", store.forcedStatus(merchantOrderNo));
        return body;
    }

    /**
     * 注入一行渠道日终对账单，用于制造 4 类对账差异。
     *
     * <p>{@code amount} 可选：对账单行不带金额时表示状态-only 差异。
     * 金额以 {@link BigDecimal} 直接绑定，Spring 会精确转换查询参数字符串，不经过 double。
     */
    @PostMapping("/control/statement")
    public Map<String, Object> statement(@RequestParam String merchantOrderNo,
                                         @RequestParam String status,
                                         @RequestParam(required = false) BigDecimal amount) {
        store.putStatementRow(merchantOrderNo, status, amount);
        Map<String, Object> body = new HashMap<>();
        body.put("statementSize", store.statementSize());
        return body;
    }

    /** 清空渠道全部内存状态并复位丢回调开关。测试在 {@code @BeforeEach} 调用。 */
    @PostMapping("/control/reset")
    public Map<String, Object> reset() {
        store.clear();
        Map<String, Object> body = new HashMap<>();
        body.put("reset", true);
        return body;
    }

    /** 当前渠道侧状态快照，作为测试取证用。 */
    @GetMapping("/control/snapshot")
    public Map<String, Object> snapshot() {
        Map<String, Object> body = new HashMap<>();
        body.put("dropCallback", store.isDropCallback());
        body.put("statement", store.statement());
        body.put("statementSize", store.statementSize());
        return body;
    }
}
