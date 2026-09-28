package com.payrecon.service;

import com.payrecon.enums.PayStatus;
import com.payrecon.mapper.AccountEntryMapper;
import com.payrecon.mapper.PayAccountMapper;
import com.payrecon.entity.AccountEntry;
import com.payrecon.entity.PayAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 入账：把「支付成功」变成账户余额上的一笔钱。
 *
 * <p>为什么需要这一层而不是在状态推进里直接加余额：
 * 规格要求证明「入账动作只执行 1 次」。光看状态位只能间接推断，
 * 因此这里把入账做成一条带唯一索引（{@code account_entry.uk_order_id}）的流水，
 * 使「只入账一次」可以被 {@code SELECT COUNT(*)} 直接数出来。
 *
 * <p>双层防护（这是本项目「不重复扣款」的核心）：
 * <ol>
 *   <li><b>门</b>：只有条件更新抢到跃迁（影响行数 = 1）的调用方才被允许走到这里；</li>
 *   <li><b>背</b>：即使并发下有两个调用方都走到了这里，{@code uk_order_id}
 *       保证只有一个 INSERT 成功，另一个抛 {@link DuplicateKeyException} 并被判定为「已入账」。</li>
 * </ol>
 * 注意：这里**不能**用「先查 count 再 insert」判空——并发下两个线程都会查到 0，
 * 然后各插一笔。必须让数据库唯一索引来裁决。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountEntryMapper accountEntryMapper;
    private final PayAccountMapper payAccountMapper;

    /**
     * 对指定订单入账，天然幂等。
     *
     * @return true 表示本次真的产生了入账；false 表示此前已入账（重复回调被挡住，未重复加钱）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean credit(Long orderId, BigDecimal amount, String accountNo) {
        if (amount == null) {
            throw new IllegalArgumentException("入账金额不得为空");
        }

        AccountEntry entry = new AccountEntry();
        entry.setOrderId(orderId);
        entry.setAmount(amount);
        entry.setEntryType("CREDIT");
        entry.setCreatedAt(LocalDateTime.now());

        try {
            accountEntryMapper.insert(entry);
        } catch (DuplicateKeyException e) {
            // 精确捕获：uk_order_id 撞了，说明这笔已经入过账。
            // 不允许用 catch (Exception) 大网兜住——那会把真实的数据库故障也吞成"已入账"。
            log.info("订单 {} 已入账过，本次重复入账被唯一索引拦截，不重复加钱", orderId);
            return false;
        }

        payAccountMapper.increaseBalance(accountNo, amount, LocalDateTime.now());
        log.info("订单 {} 入账成功，金额 {}，账户 {}", orderId, amount, accountNo);
        return true;
    }

    /** 供对账/测试读取当前余额。 */
    public BigDecimal balanceOf(String accountNo) {
        PayAccount account = payAccountMapper.selectByAccountNo(accountNo);
        if (account == null) {
            throw new IllegalStateException("账户不存在: " + accountNo);
        }
        return account.getBalance();
    }

    /** 判断某状态是否值得尝试入账（只有 SUCCESS 才入账）。 */
    public static boolean shouldCredit(PayStatus toStatus) {
        return toStatus == PayStatus.SUCCESS;
    }
}
