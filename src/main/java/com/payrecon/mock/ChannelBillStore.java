package com.payrecon.mock;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 渠道侧进程内状态与账单注册表 —— 渠道 Mock 的“故障注入底座”（fault-injection substrate）。
 *
 * <p><b>这不是一个真实的渠道对接实现。</b>它只是把渠道模拟器需要维护的几类内存状态集中到一个
 * 线程安全的组件里，供渠道 Mock 控制器与对账服务共同消费：
 * <ul>
 *   <li>渠道已记账的账单（{@code bills}）：模拟渠道自己的订单视图；</li>
 *   <li>强制查询状态（{@code forcedStatuses}）：让查询接口返回指定状态，包括 {@code NOT_FOUND}；</li>
 *   <li>注入的对账单行（{@code statement}）：模拟渠道下发的日终对账单；</li>
 *   <li>丢回调开关（{@code dropCallback}）：模拟渠道回调丢失的故障。</li>
 * </ul>
 *
 * <p>之所以做成单一组件，是为了让并发测试能够<b>确定性地注入异常</b>：测试线程可以在任意时刻
 * 写入账单行或翻转开关，而业务线程同时在读取，因此内部状态一律使用
 * {@link java.util.concurrent.ConcurrentHashMap}，且对外返回快照，避免
 * {@link java.util.ConcurrentModificationException}。
 */
@Slf4j
@Component
public class ChannelBillStore {

    /** 渠道已记账的账单：商户订单号 -> 渠道账单。 */
    private final Map<String, ChannelBill> bills = new ConcurrentHashMap<>();

    /** 强制返回的渠道查询状态：商户订单号 -> 字符串状态（可为 NOT_FOUND）。 */
    private final Map<String, String> forcedStatuses = new ConcurrentHashMap<>();

    /** 注入的对账单行（渠道账单）：商户订单号 -> 渠道账单。 */
    private final Map<String, ChannelBill> statementRows = new ConcurrentHashMap<>();

    /** 丢回调开关：true 表示渠道不回回调（故障注入）。 */
    private volatile boolean dropCallback;

    /**
     * 清空全部状态：账单、强制状态、对账单行，并把丢回调开关复位为 false。
     * 测试通常在 {@code @BeforeEach} 中调用。
     */
    public void clear() {
        bills.clear();
        forcedStatuses.clear();
        statementRows.clear();
        this.dropCallback = false;
        log.debug("渠道模拟状态已清空");
    }

    /**
     * 记录一条渠道账单。
     *
     * @param bill 渠道账单，不允许为 null
     */
    public void putBill(ChannelBill bill) {
        bills.put(bill.merchantOrderNo(), bill);
    }

    /**
     * 读取渠道账单。
     *
     * @param merchantOrderNo 商户订单号
     * @return 对应账单；不存在时返回 null
     */
    public ChannelBill getBill(String merchantOrderNo) {
        return bills.get(merchantOrderNo);
    }

    /**
     * 强制指定订单的渠道查询状态。
     *
     * <p>这里<b>故意不做</b> {@code PayStatus} 校验：字面量 {@code NOT_FOUND} 是合法取值，
     * 表示“渠道侧不存在该订单”，正是测试需要覆盖的场景。
     *
     * @param merchantOrderNo 商户订单号
     * @param status          强制返回的状态字符串，原样存储
     */
    public void forceStatus(String merchantOrderNo, String status) {
        forcedStatuses.put(merchantOrderNo, status);
        log.debug("强制渠道状态 {} -> {}", merchantOrderNo, status);
    }

    /**
     * 读取被强制的渠道查询状态。
     *
     * @param merchantOrderNo 商户订单号
     * @return 被强制的状态字符串；未被强制时返回 null
     */
    public String forcedStatus(String merchantOrderNo) {
        return forcedStatuses.get(merchantOrderNo);
    }

    /** 清空全部强制状态。 */
    public void clearForcedStatuses() {
        forcedStatuses.clear();
    }

    /** @return 当前是否处于“丢回调”故障状态 */
    public boolean isDropCallback() {
        return dropCallback;
    }

    /**
     * 设置/取消“丢回调”故障开关。
     *
     * @param drop true 表示渠道不回回调
     */
    public void setDropCallback(boolean drop) {
        this.dropCallback = drop;
        log.debug("丢回调开关置为 {}", drop);
    }

    /**
     * 写入一行渠道对账单（模拟日终对账单下发）。
     *
     * <p>同一商户订单号会被覆盖：测试一次只注入一个具体的差异场景。
     * 交易流水号由商户订单号确定性生成，形如 {@code STMT-<merchantOrderNo>}。
     *
     * <p>{@code amount} 允许为 null：对账单行可能不带金额，状态-only / {@code ONE_SIDE_ONLY}
     * 场景正是以此表达的。
     *
     * @param merchantOrderNo 商户订单号
     * @param status          对账单上的渠道状态
     * @param amount          对账单上的金额，可为 null
     */
    public void putStatementRow(String merchantOrderNo, String status, BigDecimal amount) {
        statementRows.put(merchantOrderNo,
                new ChannelBill(merchantOrderNo, status, amount, "STMT-" + merchantOrderNo));
    }

    /**
     * 获取当前对账单快照。
     *
     * <p>返回值是防御性拷贝：调用方在遍历期间，测试线程可以继续注入新的对账单行，
     * 而不会抛出 {@link java.util.ConcurrentModificationException}。
     *
     * @return 渠道账单映射（商户订单号 -> 渠道账单）的可变副本快照
     */
    public Map<String, ChannelBill> statement() {
        return new LinkedHashMap<>(statementRows);
    }

    /** @return 当前对账单行数 */
    public int statementSize() {
        return statementRows.size();
    }
}
