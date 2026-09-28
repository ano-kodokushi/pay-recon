package com.payrecon.service;

import com.payrecon.entity.PayFlow;
import com.payrecon.entity.PayOrder;
import com.payrecon.enums.EventType;
import com.payrecon.enums.FlowSource;
import com.payrecon.enums.PayStatus;
import com.payrecon.mapper.PayFlowMapper;
import com.payrecon.mapper.PayOrderMapper;
import com.payrecon.state.OrderStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 全项目**唯一**的状态推进服务。
 *
 * <p>规格 §3 明确要求：任务 2（支付回调）、任务 3（主动查询兜底）、任务 4（对账补偿）
 * 必须复用同一个状态推进逻辑，**不得复制一份**。这个类就是那个"同一份"。
 * 任何写 {@code pay_order.status} 的代码路径都必须经过 {@link #advance}，
 * 从而无法绕过 {@link OrderStateMachine#canTransit}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdvanceService {

    private final PayOrderMapper payOrderMapper;
    private final PayFlowMapper payFlowMapper;
    private final OrderStateMachine stateMachine;
    private final AccountService accountService;

    /** 状态推进的结果。四个分支必须可区分，调用方才能各自正确应答。 */
    public enum Outcome {
        /** 本次抢到了跃迁，并且（若目标是 SUCCESS）完成了入账。 */
        VALID_ADVANCED,
        /** 订单已在终态或目标态，本次是重复回调/重复查询，未做任何业务动作。 */
        DUPLICATE_IGNORED,
        /** 状态机判定 from -> to 非法，已记拒绝日志，未写库。 */
        ILLEGAL_TRANSITION,
        /** 商户订单号查不到对应订单。 */
        ORDER_NOT_FOUND
    }

    /** 推进结果，带订单号/订单 ID/起始状态，便于调用方与测试取证。 */
    public record AdvanceResult(Outcome outcome,
                                Long orderId,
                                String merchantOrderNo,
                                PayStatus fromStatus,
                                PayStatus toStatus,
                                boolean credited) {

        public boolean isAdvanced() {
            return outcome == Outcome.VALID_ADVANCED;
        }
    }

    /**
     * 唯一的推进入口。
     *
     * <p>执行顺序（顺序不可颠倒，规格 §0.4 / §3 任务 2）：
     * <ol>
     *   <li>先落 {@code pay_flow} 原始报文（{@code raw_payload} 保存原文），保证可追溯、可复算；</li>
     *   <li>状态机校验 {@code canTransit}，非法则记拒绝日志、直接返回，不抛给渠道方；</li>
     *   <li>执行条件更新 {@code ... WHERE merchant_order_no = ? AND status IN ('CREATED','PAYING')}，
     *       拿影响行数；</li>
     *   <li>影响行数 = 1 → 本次是有效推进：目标为 SUCCESS 时入账，再写一条状态变更流水；</li>
     *   <li>影响行数 = 0 → 已是终态，本次为重复回调：**不执行入账**，写一条"重复被忽略"流水。</li>
     * </ol>
     *
     * <p>第 1 步与第 4/5 步各自会在 {@code pay_flow} 落一行，因此一次回调最多产生两行：
     * 一行原始报文（无条件），一行推进或"忽略"（按结果二选一）。
     *
     * @param merchantOrderNo 商户订单号
     * @param toStatus        目标状态
     * @param eventType       事件类型口径（CALLBACK / QUERY / RECONCILE）
     * @param source          来源（CALLBACK / QUERY / RECONCILE）
     * @param rawPayload      渠道原始报文原文；调用方无报文时传 null
     * @param channelTradeNo  渠道流水号；调用方没有时传 null
     */
    @Transactional(rollbackFor = Exception.class)
    public AdvanceResult advance(String merchantOrderNo,
                                 PayStatus toStatus,
                                 EventType eventType,
                                 FlowSource source,
                                 String rawPayload,
                                 String channelTradeNo) {

        PayOrder order = payOrderMapper.selectByMerchantOrderNo(merchantOrderNo);
        if (order == null) {
            log.warn("推进失败：订单不存在 merchantOrderNo={}", merchantOrderNo);
            return new AdvanceResult(Outcome.ORDER_NOT_FOUND, null, merchantOrderNo, null, toStatus, false);
        }

        // ---- 步骤 1：先落流水，保存原始报文（无条件，重复回调也要留痕） ----
        insertFlow(order.getId(), eventType, source, null, null, rawPayload);

        PayStatus from = PayStatus.of(order.getStatus());
        if (from == null) {
            log.warn("推进失败：订单 {} 状态值无法识别: {}", merchantOrderNo, order.getStatus());
            return new AdvanceResult(Outcome.ILLEGAL_TRANSITION, order.getId(), merchantOrderNo, null, toStatus, false);
        }

        // ---- 步骤 2a：重复回调（目标状态 == 当前状态）不是"非法跃迁"，而是"重复被忽略" ----
        // 规格 §3 任务 2 第 4 步要求：影响行数 = 0 时写一条"重复回调被忽略"的流水。
        // 但若把 SUCCESS -> SUCCESS 判定为非法跃迁并在状态机处直接返回，就永远走不到那一步，
        // 重复回调将没有可识别的留痕。订单已经处在目标状态时，本就没有任何迁移要执行，
        // 因此这里先把它归类为重复：不写状态、不入账，只落一条"忽略"流水。
        if (from == toStatus) {
            insertFlow(order.getId(), eventType, source, from, toStatus, null);
            log.info("[advance] 订单 {} 重复推进被忽略（读到的状态已等于目标 {}），未执行入账",
                    merchantOrderNo, toStatus);
            return new AdvanceResult(Outcome.DUPLICATE_IGNORED, order.getId(), merchantOrderNo, from, toStatus, false);
        }

        // ---- 步骤 2b：集中式状态机校验。非法迁移只记日志，不抛给渠道方 ----
        if (!stateMachine.canTransit(from, toStatus)) {
            // canTransit 内部已打过 WARN 拒绝日志
            return new AdvanceResult(Outcome.ILLEGAL_TRANSITION, order.getId(), merchantOrderNo, from, toStatus, false);
        }

        // ---- 步骤 3：条件更新，按影响行数判定 ----
        int affected = payOrderMapper.advanceStatus(merchantOrderNo, toStatus.name(), channelTradeNo);

        if (affected == 1) {
            // ---- 步骤 4：本次抢到跃迁 → 入账 + 写状态变更流水 ----
            boolean credited = false;
            if (AccountService.shouldCredit(toStatus)) {
                credited = accountService.credit(order.getId(), order.getAmount(), resolveAccountNo());
            }
            insertFlow(order.getId(), eventType, source, from, toStatus, null);
            log.info("订单 {} 状态推进 {} -> {}（影响行数=1，入账={}）",
                    merchantOrderNo, from, toStatus, credited);
            return new AdvanceResult(Outcome.VALID_ADVANCED, order.getId(), merchantOrderNo, from, toStatus, credited);
        }

        // ---- 步骤 5：影响行数 = 0 → 已经是终态/目标态，重复回调，绝不重复入账 ----
        // 关键：这里必须【重新读取】订单当前状态，不能沿用步骤 1 之前那次读到的 from。
        // 并发场景下 from 是陈旧快照：多个线程可能都读到 PAYING，只有一个的影响行数是 1，
        // 其余都是 0。若把这些"没抢到跃迁"的调用也记成 PAYING -> SUCCESS，
        // 审计流水就会出现多条并不存在的跃迁，而"只推进一次"恰恰要靠流水来证明。
        // 因此把它们记为 from == to（当时实际所处的状态），表明这是一次空推进。
        PayOrder current = payOrderMapper.selectByMerchantOrderNo(merchantOrderNo);
        PayStatus actualFrom = current == null ? from : PayStatus.of(current.getStatus());
        if (actualFrom == null) {
            actualFrom = from;
        }
        insertFlow(order.getId(), eventType, source, actualFrom, actualFrom, null);
        log.info("订单 {} 重复推进被忽略（影响行数=0，实际状态 {}，目标 {}），未执行入账",
                merchantOrderNo, current == null ? "未知" : current.getStatus(), toStatus);
        return new AdvanceResult(Outcome.DUPLICATE_IGNORED, order.getId(), merchantOrderNo, actualFrom, toStatus, false);
    }

    /**
     * 便捷重载：回调/查询/对账都用同一实现，只是 eventType 与 source 不同。
     */
    public AdvanceResult advance(String merchantOrderNo,
                                 PayStatus toStatus,
                                 EventType eventType,
                                 FlowSource source) {
        return advance(merchantOrderNo, toStatus, eventType, source, null, null);
    }

    /** 只追加，永不修改、永不删除。 */
    private void insertFlow(Long orderId, EventType eventType, FlowSource source,
                            PayStatus from, PayStatus to, String rawPayload) {
        PayFlow flow = new PayFlow();
        flow.setOrderId(orderId);
        flow.setEventType(eventType.name());
        flow.setSource(source.name());
        flow.setFromStatus(from == null ? null : from.name());
        flow.setToStatus(to == null ? null : to.name());
        flow.setRawPayload(rawPayload);
        flow.setCreatedAt(LocalDateTime.now());
        payFlowMapper.insert(flow);
    }

    private String resolveAccountNo() {
        return "ACC-001";
    }
}
