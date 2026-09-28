package com.payrecon.job;

import com.payrecon.config.QueryJobProperties;
import com.payrecon.service.QueryJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 任务3「主动查询兜底」的定时器。
 *
 * <p>本类只做薄委托：判断开关 → 调用 {@link QueryJobService#runOnceDetailed()} → 打一行汇总日志。
 * 全部业务逻辑在 {@code QueryJobService} 里，状态推进则唯一地走
 * {@code AdvanceService.advance(...)}（与回调同一个方法）。
 *
 * <p>开关在<b>调用时</b>读取，而不是用 {@code @ConditionalOnProperty} 决定 Bean 是否创建：
 * 前者在同一个 Spring 上下文内可切换（测试可以运行时翻转 app.query-job.enabled 再手动触发），
 * 后者需要新的上下文才能生效（{@code @ConditionalOnProperty} 是启动期条件）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryJobScheduler {

    private final QueryJobService queryJobService;
    private final QueryJobProperties queryJobProperties;

    /**
     * 按固定延迟周期性执行主动查询兜底。
     *
     * <p>整个方法体包在 try/catch 里：{@code @Scheduled} 任务一旦抛出未捕获异常，
     * Spring 会持续抛错并可能让该任务从此不再被调度，兜底会<b>静默失效</b>，
     * 因此这里必须吞掉异常并记 ERROR，保证下一轮仍会执行。
     */
    @Scheduled(fixedDelayString = "${app.query-job.fixed-delay-ms:5000}")
    public void queryStalePaying() {
        if (!queryJobProperties.isEnabled()) {
            log.debug("主动查询兜底任务已关闭（app.query-job.enabled=false），本轮跳过");
            return;
        }
        try {
            QueryJobService.QueryJobResult result = queryJobService.runOnceDetailed();
            log.info("主动查询兜底：扫描 {} 单，推进 {}，重复忽略 {}，非法跃迁 {}，订单缺失 {}，渠道缺失 {}",
                    result.scanned(), result.advanced(), result.duplicateIgnored(),
                    result.illegalTransition(), result.orderNotFound(), result.channelNotFound());
        } catch (Exception e) {
            log.error("主动查询兜底任务异常，本轮中止（不影响下一轮调度）", e);
        }
    }
}
