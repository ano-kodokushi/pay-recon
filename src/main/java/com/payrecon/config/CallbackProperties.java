package com.payrecon.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 回调相关配置，绑定 {@code application.yml} 中的 {@code app.callback.*}。
 *
 * <p>位于 {@code com.payrecon.config} 包下，因此被启动类上的
 * {@code @ConfigurationPropertiesScan("com.payrecon.config")} 自动扫描到；
 * 额外再标一个 {@link Component}，保证即使扫描条件变化也能被注册为 Bean。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.callback")
public class CallbackProperties {

    /** 回调验签用的 HMAC-SHA256 密钥。 */
    private String secret;

    /** 我方回调地址，渠道 mock 会向该地址推送通知。 */
    private String selfUrl;
}
