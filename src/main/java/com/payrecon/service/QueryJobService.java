package com.payrecon.service;

import com.payrecon.config.QueryJobProperties;
import com.payrecon.entity.PayOrder;
import com.payrecon.enums.EventType;
import com.payrecon.enums.FlowSource;
import com.payrecon.enums.PayStatus;
import com.payrecon.mapper.PayOrderMapper;
import com.payrecon.mock.ChannelBill;
import com.payrecon.mock.ChannelBillStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 任务3「主动查询兜底」的确定性核心。
 *
 * <p>回调丢失时，订单会一直停在 {@code PAYING}。本服务主动向渠道查询真实状态，
 * 然后调用 {@code AdvanceService.advance(...)} —— <b>与回调入口使用的是同一个方法</b>，
 * 全项目只有这一份状态推进实现（见 {@link AdvanceService#advance(String, PayStatus, EventType, FlowSource, String, String)}）。
 *
 * <p>本类<b>不含</b>任何 {@code UPDATE pay_order} 语句、不自行做状态条件判断、
 * 不写 {@code pay_flow}：这些全部属于 {@code AdvanceService}。
 * 这里只负责「问渠道 → 翻译状态 → 转交给唯一推进入口 → 计数」。
 *
 * <p>{@link #runOnceDetailed()} 是测试直接调用的确定性入口；定时器只是它的薄包装。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryJobService {

    /** 渠道侧「查无此单」的字面量约定，与 {@code ChannelBillStore.forceStatus} 一致。 */
    private static final String CHANNEL_NOT_FOUND = "NOT_FOUND";

    private final PayOrderMapper payOrderMapper;
    private final AdvanceService advanceService;
    private final ChannelBillStore store;
    private final QueryJobProperties props;

    /**
     * 单轮主动查询的结果计数，供测试与日志取证。
     *
     * @param scanned           本轮从库里捞出的 PAYING 超时单数
     * @param advanced          实际推进成功的单数（{@code Outcome.VALID_ADVANCED}）
     * @param duplicateIgnored  重复推进被忽略的单数（{@code Outcome.DUPLICATE_IGNORED}）
     * @param illegalTransition 状态机判定非法跃迁的单数（{@code Outcome.ILLEGAL_TRANSITION}）
     * @param orderNotFound     本地订单不存在（{@code Outcome.ORDER_NOT_FOUND}）
     * @param channelNotFound   渠道无记录、渠道返回 NOT_FOUND、或渠道状态无法解析的单数（一律跳过，不推进）
     */
    public record QueryJobResult(int scanned,
                                 int advanced,
                                 int duplicateIgnored,
                                 int illegalTransition,
                                 int orderNotFound,
                                 int channelNotFound) {
    }

    /**
     * 执行一轮主动查询兜底。
     *
     * @return 本轮真正被推进的订单数（{@code Outcome.VALID_ADVANCED} 的条数）
     */
    public int runOnce() {
        return runOnceDetailed().advanced();
    }

    /**
     * 执行一轮主动查询兜底，并返回全部计数。
     *
     * <p>流程：
     * <ol>
     *   <li>捞 {@code status='PAYING'} 且 {@code updated_at} 超时的单；</li>
     *   <li>逐单向渠道查询真实状态（强制状态优先，其次渠道账单，都没有则视作查无此单）；</li>
     *   <li>渠道说查无此单 / 状态无法解析 → 跳过，<b>绝不推进</b>；</li>
     *   <li>否则把状态原样交给 {@code AdvanceService.advance(...)}，由它独占完成状态推进。</li>
     * </ol>
     *
     * <p>注意：{@code selectStalePaying} 的 SQL 里已经写死 {@code status = 'PAYING'}，
     * 终态单（SUCCESS/FAILED/CLOSED）根本不会被捞出来，因此本查询<b>永远不可能</b>把终态单改回非终态；
     * 即使真被捞到，{@code AdvanceService} 的条件 UPDATE 与状态机也会拦住它。
     *
     * @return 本轮各分支的计数
     */
    public QueryJobResult runOnceDetailed() {
        List<PayOrder> stale = payOrderMapper.selectStalePaying(props.getStaleMinutes(), props.getBatchSize());
        int scanned = stale == null ? 0 : stale.size();

        int advanced = 0;
        int duplicateIgnored = 0;
        int illegalTransition = 0;
        int orderNotFound = 0;
        int channelNotFound = 0;

        if (scanned == 0) {
            log.debug("主动查询兜底：本轮无超时未决单（staleMinutes={}, batchSize={}）",
                    props.getStaleMinutes(), props.getBatchSize());
            return new QueryJobResult(0, 0, 0, 0, 0, 0);
        }

        for (PayOrder order : stale) {
            String merchantOrderNo = order.getMerchantOrderNo();

            // ---- 问渠道：a) 强制状态优先 b) 渠道账单 c) 都没有 → 查无此单 ----
            String channelStatus = store.forcedStatus(merchantOrderNo);
            ChannelBill bill = store.getBill(merchantOrderNo);
            String tradeNo = bill == null ? null : bill.tradeNo();

            if (channelStatus == null) {
                channelStatus = bill == null ? null : bill.status();
            }
            if (channelStatus == null) {
                channelStatus = CHANNEL_NOT_FOUND;
            }

            // 渠道明确表示没有这笔单 → 不能凭猜测推进，跳过
            if (CHANNEL_NOT_FOUND.equalsIgnoreCase(channelStatus.trim())) {
                channelNotFound++;
                log.info("主动查询：订单 {} 渠道侧无记录（channelStatus=NOT_FOUND），跳过不推进", merchantOrderNo);
                continue;
            }

            // 渠道状态无法映射为本地 PayStatus → 同样视为“渠道没有可用答案”，跳过
            PayStatus toStatus = PayStatus.of(channelStatus);
            if (toStatus == null) {
                channelNotFound++;
                log.warn("主动查询：订单 {} 渠道返回无法识别的状态 {}，跳过不推进", merchantOrderNo, channelStatus);
                continue;
            }

            // 原始报文：主动查询场景下“原文”就是渠道本次查询应答，如实记录为字符串
            String rawPayload = "{\"source\":\"ACTIVE_QUERY\",\"merchantOrderNo\":\""
                    + merchantOrderNo + "\",\"channelStatus\":\"" + channelStatus
                    + "\",\"tradeNo\":" + (tradeNo == null ? "null" : "\"" + tradeNo + "\"") + "}";

            // ---- 唯一的推进路径：与回调共用 AdvanceService.advance(...) ----
            AdvanceService.AdvanceResult result = advanceService.advance(
                    merchantOrderNo, toStatus, EventType.QUERY, FlowSource.QUERY, rawPayload, tradeNo);

            switch (result.outcome()) {
                case VALID_ADVANCED -> advanced++;
                case DUPLICATE_IGNORED -> duplicateIgnored++;
                case ILLEGAL_TRANSITION -> illegalTransition++;
                case ORDER_NOT_FOUND -> orderNotFound++;
            }

            log.info("主动查询：订单 {} 渠道状态 {} -> 推进结果 {}", merchantOrderNo, channelStatus, result.outcome());
        }

        QueryJobResult summary = new QueryJobResult(scanned, advanced, duplicateIgnored,
                illegalTransition, orderNotFound, channelNotFound);
        log.debug("主动查询兜底完成：{}", summary);
        return summary;
    }
}
