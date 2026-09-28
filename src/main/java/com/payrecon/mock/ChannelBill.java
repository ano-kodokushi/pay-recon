package com.payrecon.mock;

import java.math.BigDecimal;

/**
 * 渠道侧账单（渠道自己的订单视图）中的一行记录。
 *
 * <p>本记录表示<b>渠道方</b>所看到的某一笔订单的账单数据，它与我们本地库中的 {@code pay_order}
 * 是<b>刻意分离的两个事实来源</b>（two separate sources of truth）。整个对账（reconciliation）的
 * 意义就在于：这两边可能不一致，而我们必须能够把它们的不一致检测出来。
 *
 * <p>因此这里不携带任何本地订单主键、不依赖本地枚举，只保留渠道账单本身会有的字段，
 * 以便测试可以自由构造“渠道有而本地没有”“本地有而渠道没有”“金额不一致”“状态不一致”等差异场景。
 *
 * @param merchantOrderNo 商户订单号，作为渠道账单与本地订单之间唯一的对账关联键
 * @param status          渠道侧订单状态（字符串原样保留，不强制映射为本地枚举）
 * @param amount          渠道侧金额，仅使用 {@link java.math.BigDecimal}，禁止使用 float/double 表示金额
 * @param tradeNo         渠道侧交易流水号
 */
public record ChannelBill(String merchantOrderNo, String status, BigDecimal amount, String tradeNo) {
}
