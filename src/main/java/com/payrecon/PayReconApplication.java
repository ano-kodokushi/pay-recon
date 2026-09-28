package com.payrecon;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 支付订单与对账模块。
 *
 * <p>范围：下单幂等 → 支付回调 → 集中状态机 → 主动查询兜底 → 对账核销。
 * 重点解决重复回调与并发场景下的重复扣款。
 *
 * <p>明确不做：前端/管理后台、登录鉴权、真实对接第三方渠道、真实退款、
 * 多渠道路由、分账清算、MQ、分布式事务、微服务拆分、注册中心、分库分表。
 */
@SpringBootApplication
@MapperScan("com.payrecon.mapper")
@ConfigurationPropertiesScan("com.payrecon.config")
@EnableScheduling
public class PayReconApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayReconApplication.class, args);
    }
}
