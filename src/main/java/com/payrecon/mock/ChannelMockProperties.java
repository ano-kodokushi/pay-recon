package com.payrecon.mock;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 渠道 Mock 配置，绑定 {@code application.yml} 中的 {@code app.channel-mock.*}。
 *
 * <p>只提供<b>代码内默认值</b>，不改动 {@code application.yml}：即使配置项缺失也能直接启动并
 * 完成端到端自回调，保证测试环境零配置可用。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.channel-mock")
public class ChannelMockProperties {

    /** 渠道名称，出现在渠道 Mock 的响应与日志中。 */
    private String name = "MOCK-CHANNEL";

    /** 渠道回调我们自己的地址；留空时使用 {@link #effectiveCallbackUrl()} 的默认值。 */
    private String selfCallbackUrl;

    /**
     * 解析实际使用的回调地址。
     *
     * @return 配置了 {@code selfCallbackUrl} 时返回其原值；否则返回默认的本机回调地址
     */
    public String effectiveCallbackUrl() {
        return (selfCallbackUrl == null || selfCallbackUrl.isBlank())
                ? "http://127.0.0.1:8080/api/pay/callback"
                : selfCallbackUrl;
    }
}
