package com.payrecon.service;

import com.payrecon.dto.ReconcileResult;
import com.payrecon.entity.PayOrder;
import com.payrecon.entity.ReconcileDetail;
import com.payrecon.enums.DiffType;
import com.payrecon.enums.EventType;
import com.payrecon.enums.FlowSource;
import com.payrecon.enums.PayStatus;
import com.payrecon.mapper.PayOrderMapper;
import com.payrecon.mapper.ReconcileDetailMapper;
import com.payrecon.mock.ChannelBill;
import com.payrecon.mock.ChannelBillStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对账：差异发现 + 补偿（不做真实退款）。
 *
 * <p>两个事实来源：我方 {@code pay_order} 中该账单日创建的订单，与渠道下发的日终对账单
 * （mock 由 {@link ChannelBillStore#statement()} 提供，测试可注入差异）。逐笔比对后把差异写进
 * {@code reconcile_detail}，再按差异类型决定是否补偿。
 *
 * <h3>4 类差异的判定</h3>
 * <ul>
 *   <li>{@code AMOUNT_MISMATCH}：两侧都有该单，且金额不一致。金额比较一律用
 *       {@link BigDecimal#compareTo}，<b>绝不用 {@link BigDecimal#equals}</b>——
 *       {@code new BigDecimal("10.0").equals(new BigDecimal("10.00"))} 为 false（scale 不同），
 *       用 equals 会把每一笔正常订单都误报成金额差异。任一侧金额为 null 时跳过金额比较。</li>
 *   <li>{@code LOCAL_OK_CHANNEL_FAIL}：我方已是 SUCCESS，渠道状态不是成功。</li>
 *   <li>{@code CHANNEL_OK_LOCAL_FAIL}：渠道是成功，我方还未到 SUCCESS。</li>
 *   <li>{@code ONE_SIDE_ONLY}：只有一侧有该单（我方有渠道账单无，或渠道账单有我方无）。</li>
 * </ul>
 *
 * <h3>为什么只有一类会被自动补偿</h3>
 * 只有"渠道成功 / 我方非终态"是安全的：渠道明确收到了钱，把我方状态补成 SUCCESS 不会凭空造钱。
 * 其余三类（尤其是我方成功、渠道无此单）可能涉及真实资金差错，<b>只标记差异、置
 * {@code resolved = 0} 待人工处理，绝不自动改状态</b>。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconcileService {

    private final PayOrderMapper payOrderMapper;
    private final ReconcileDetailMapper reconcileDetailMapper;
    private final ChannelBillStore channelBillStore;
    private final AdvanceService advanceService;

    /**
     * 差异发现：比对我方账单与渠道账单，把差异写入 {@code reconcile_detail}。
     *
     * <p><b>已知限制</b>：同一天重复执行会重复插入差异明细，本方法不做去重
     * （差异明细是审计证据，因此也不提供 DELETE/UPDATE 清理路径）。
     *
     * @param billDate 账单日，ISO {@code yyyy-MM-dd}
     * @return 按差异类型分组的计数结果
     */
    public ReconcileResult reconcile(String billDate) {
        ReconcileResult result = ReconcileResult.empty(billDate);

        List<PayOrder> localOrders = payOrderMapper.selectByCreatedDate(billDate);
        Map<String, PayOrder> localByNo = new LinkedHashMap<>();
        for (PayOrder order : localOrders) {
            localByNo.put(order.getMerchantOrderNo(), order);
        }

        // 渠道账单有两种可能来源：测试注入的对账单行优先，其次渠道自己记的账单
        Map<String, ChannelBill> statement = channelBillStore.statement();
        Map<String, ChannelBill> channelByNo = new LinkedHashMap<>(statement);
        for (Map.Entry<String, ChannelBill> e : statement.entrySet()) {
            if (e.getValue() == null) {
                ChannelBill bill = channelBillStore.getBill(e.getKey());
                if (bill != null) {
                    channelByNo.put(e.getKey(), bill);
                }
            }
        }

        // 两侧订单号的并集
        Set<String> allNos = new LinkedHashSet<>(localByNo.keySet());
        allNos.addAll(channelByNo.keySet());

        List<String> orderNos = new ArrayList<>();
        int total = 0;

        for (String no : allNos) {
            PayOrder local = localByNo.get(no);
            ChannelBill channel = channelByNo.get(no);

            DiffType diffType = classify(local, channel);
            if (diffType == null) {
                continue;
            }

            ReconcileDetail detail = new ReconcileDetail();
            detail.setOrderId(local == null ? null : local.getId());
            detail.setMerchantOrderNo(no);
            detail.setBillDate(LocalDate.parse(billDate));
            detail.setLocalStatus(local == null ? null : local.getStatus());
            detail.setChannelStatus(channel == null ? null : channel.status());
            detail.setDiffType(diffType.name());
            // 发现即视为待处理；只有 CHANNEL_OK_LOCAL_FAIL 补偿成功后才会被置 1
            detail.setResolved(0);
            detail.setCreatedAt(LocalDateTime.now());
            reconcileDetailMapper.insert(detail);

            orderNos.add(no);
            total++;
            switch (diffType) {
                case LOCAL_OK_CHANNEL_FAIL -> result.setLocalOkChannelFail(result.getLocalOkChannelFail() + 1);
                case CHANNEL_OK_LOCAL_FAIL -> result.setChannelOkLocalFail(result.getChannelOkLocalFail() + 1);
                case AMOUNT_MISMATCH -> result.setAmountMismatch(result.getAmountMismatch() + 1);
                case ONE_SIDE_ONLY -> result.setOneSideOnly(result.getOneSideOnly() + 1);
            }
            log.info("[对账] 发现差异 billDate={}, merchantOrderNo={}, localStatus={}, channelStatus={}, diffType={}",
                    billDate, no, detail.getLocalStatus(), detail.getChannelStatus(), diffType);
        }

        result.setTotal(total);
        result.setOrderNos(orderNos);
        log.info("[对账] 差异发现完成 billDate={}, 差异总数={}, 我方订单数={}, 渠道账单数={}",
                billDate, total, localByNo.size(), channelByNo.size());
        return result;
    }

    /**
     * 判定单个订单的差异类型。
     *
     * @return 差异类型；两侧一致时返回 null 表示"无差异，不该写明细"
     */
    private DiffType classify(PayOrder local, ChannelBill channel) {
        boolean localSuccess = local != null && PayStatus.SUCCESS.name().equals(local.getStatus());
        boolean channelSuccess = channel != null
                && PayStatus.of(channel.status()) == PayStatus.SUCCESS;

        if (local != null && channel != null) {
            // 金额比较必须用 compareTo：equals 会把 10.0 与 10.00 判为不等
            BigDecimal localAmount = local.getAmount();
            BigDecimal channelAmount = channel.amount();
            if (localAmount != null && channelAmount != null
                    && localAmount.compareTo(channelAmount) != 0) {
                return DiffType.AMOUNT_MISMATCH;
            }
            if (localSuccess == channelSuccess) {
                return null; // 状态口径一致，无差异
            }
            return localSuccess ? DiffType.LOCAL_OK_CHANNEL_FAIL : DiffType.CHANNEL_OK_LOCAL_FAIL;
        }

        // 单边账：只有一侧存在该订单
        return DiffType.ONE_SIDE_ONLY;
    }

    /**
     * 补偿：只把"渠道成功 / 我方非终态"这一安全情形推进为 SUCCESS。
     *
     * <p>状态推进必须走 {@link AdvanceService#advance}，本类不含任何自己的状态 UPDATE SQL，
     * 从而保证对账补偿同样受集中式状态机约束（规格 §4：任何来源都不得越过状态机）。
     *
     * @param billDate 账单日
     * @return 含 {@code compensatedCount} 与 {@code stillUnresolvedCount} 的结果
     */
    public ReconcileResult compensate(String billDate) {
        ReconcileResult result = ReconcileResult.empty(billDate);

        List<ReconcileDetail> details = reconcileDetailMapper.selectByBillDate(billDate);
        int compensated = 0;

        for (ReconcileDetail detail : details) {
            if (detail.getResolved() != null && detail.getResolved() == 1) {
                continue; // 已处理过
            }
            DiffType diffType = DiffType.valueOf(detail.getDiffType());

            if (diffType != DiffType.CHANNEL_OK_LOCAL_FAIL) {
                // 其余三类只标记差异，不自动改状态，留给人工
                log.info("[对账补偿] 跳过自动补偿 merchantOrderNo={}, diffType={}（仅标记差异，resolved 保持 0）",
                        detail.getMerchantOrderNo(), diffType);
                continue;
            }

            String rawPayload = "{\"source\":\"RECONCILE\",\"merchantOrderNo\":\""
                    + detail.getMerchantOrderNo() + "\",\"diffType\":\"CHANNEL_OK_LOCAL_FAIL\""
                    + ",\"action\":\"FORCE_SUCCESS\"}";

            AdvanceService.AdvanceResult advanceResult = advanceService.advance(
                    detail.getMerchantOrderNo(),
                    PayStatus.SUCCESS,
                    EventType.RECONCILE,
                    FlowSource.RECONCILE,
                    rawPayload,
                    null);

            boolean safe = advanceResult.outcome() == AdvanceService.Outcome.VALID_ADVANCED
                    || advanceResult.outcome() == AdvanceService.Outcome.DUPLICATE_IGNORED;
            if (safe) {
                reconcileDetailMapper.markResolved(detail.getId(), 1);
                compensated++;
                log.info("[对账补偿] merchantOrderNo={} 已补偿，outcome={}，credited={}",
                        detail.getMerchantOrderNo(), advanceResult.outcome(), advanceResult.credited());
            } else {
                log.warn("[对账补偿] merchantOrderNo={} 补偿未生效，outcome={}，保持 resolved=0",
                        detail.getMerchantOrderNo(), advanceResult.outcome());
            }
        }

        List<ReconcileDetail> after = reconcileDetailMapper.selectByBillDate(billDate);
        int unresolved = (int) after.stream()
                .filter(d -> d.getResolved() == null || d.getResolved() == 0)
                .count();

        result.setCompensatedCount(compensated);
        result.setStillUnresolvedCount(unresolved);
        log.info("[对账补偿] billDate={}, 本次补偿={}, 仍待人工={}", billDate, compensated, unresolved);
        return result;
    }

    /** 供测试与接口取证：查询某账单日的全部差异明细。 */
    public List<ReconcileDetail> details(String billDate) {
        return reconcileDetailMapper.selectByBillDate(billDate);
    }

    /** 供测试断言差异条数。 */
    public int countDetails(String billDate) {
        return reconcileDetailMapper.selectByBillDate(billDate).size();
    }
}
