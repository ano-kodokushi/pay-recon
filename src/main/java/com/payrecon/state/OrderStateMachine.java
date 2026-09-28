package com.payrecon.state;

import com.payrecon.enums.PayStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 集中式支付单状态机——本项目唯一的状态迁移权威（spec §4）。
 *
 * <p>回调、定时查询、对账补偿三条链路都必须经过本类判断，不允许各自散落 if 判断。</p>
 *
 * <p><b>非法迁移绝不抛异常</b>：在渠道回调边界抛出的异常会被渠道理解为“我方处理失败”，
 * 渠道将据此无限重试。因此非法迁移只记 WARN 日志并返回 {@code false}，由调用方决定后续动作。</p>
 */
@Slf4j
@Component
public class OrderStateMachine {

    /**
     * 合法迁移表，全项目唯一定义处。
     *
     * <pre>
     * CREATED -> PAYING, CLOSED, SUCCESS   // 同步渠道可直接成功；超时关闭自 CREATED 发生
     * PAYING  -> SUCCESS, FAILED
     * SUCCESS -> (无)  终态
     * FAILED  -> (无)  终态
     * CLOSED  -> (无)  终态
     * </pre>
     */
    private static final Map<PayStatus, Set<PayStatus>> TRANSITIONS;

    static {
        Map<PayStatus, Set<PayStatus>> table = new EnumMap<>(PayStatus.class);
        table.put(PayStatus.CREATED, EnumSet.of(PayStatus.PAYING, PayStatus.CLOSED, PayStatus.SUCCESS));
        table.put(PayStatus.PAYING, EnumSet.of(PayStatus.SUCCESS, PayStatus.FAILED));
        table.put(PayStatus.SUCCESS, EnumSet.noneOf(PayStatus.class));
        table.put(PayStatus.FAILED, EnumSet.noneOf(PayStatus.class));
        table.put(PayStatus.CLOSED, EnumSet.noneOf(PayStatus.class));
        TRANSITIONS = Collections.unmodifiableMap(table);
    }

    /**
     * 合法迁移表（只读视图），供日志与测试使用。
     *
     * @return 不可修改的 状态 -> 允许的后继状态集合
     */
    public Map<PayStatus, Set<PayStatus>> transitions() {
        return TRANSITIONS;
    }

    /**
     * 判断迁移是否合法。任一参数为 null 时返回 false。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return 合法返回 true；非法记 WARN 后返回 false（不抛异常）
     */
    public boolean canTransit(PayStatus from, PayStatus to) {
        if (from == null || to == null) {
            log.warn("非法状态迁移被拒绝: {} -> {}", from, to);
            return false;
        }
        Set<PayStatus> allowed = TRANSITIONS.get(from);
        boolean legal = allowed != null && allowed.contains(to);
        if (!legal) {
            log.warn("非法状态迁移被拒绝: {} -> {}", from, to);
        }
        return legal;
    }

    /**
     * 字符串入参的便捷重载，内部走 {@link PayStatus#of(String)} 宽松解析。
     *
     * @param from 当前状态字符串，可含大小写差异
     * @param to   目标状态字符串
     * @return 合法返回 true；无法解析或非法均返回 false（不抛异常）
     */
    public boolean canTransit(String from, String to) {
        return canTransit(PayStatus.of(from), PayStatus.of(to));
    }

    /**
     * 是否为终态（无出边）。
     *
     * @param status 待判断状态，可为 null
     * @return null 返回 false，其余委托 {@link PayStatus#isTerminal()}
     */
    public boolean isTerminal(PayStatus status) {
        return status != null && status.isTerminal();
    }
}
