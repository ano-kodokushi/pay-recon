package com.payrecon.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 任务3「主动查询兜底」的配置项，前缀 {@code app.query-job}。
 *
 * <p>本类位于 {@code com.payrecon.config} 包下，由启动类上已有的
 * {@code @ConfigurationPropertiesScan("com.payrecon.config")} 自动登记，
 * 无需额外的 {@code @EnableConfigurationProperties}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.query-job")
public class QueryJobProperties {

    /** 定时任务开关。测试里置 false 可关掉调度，再手动调 QueryJobService.runOnce() 做确定性验证。 */
    private boolean enabled = true;

    /** 两次执行之间的固定延迟（毫秒）。 */
    private long fixedDelayMs = 5000;

    /** 只捞 updated_at 超过这个分钟数还停在 PAYING 的单。 */
    private int staleMinutes = 1;

    /** 单轮最多捞取多少单，避免一次查询压垮渠道。 */
    private int batchSize = 200;
}
