package com.payrecon.service;

import com.payrecon.dto.OrderCreateRequest;
import com.payrecon.dto.OrderCreateResponse;
import com.payrecon.entity.PayFlow;
import com.payrecon.entity.PayOrder;
import com.payrecon.enums.EventType;
import com.payrecon.enums.FlowSource;
import com.payrecon.enums.PayStatus;
import com.payrecon.mapper.PayFlowMapper;
import com.payrecon.mapper.PayOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 下单服务。核心职责：让同一个 merchantOrderNo 的并发请求收敛到同一笔订单。
 *
 * <h3>幂等的实现口径（规格 §3 任务1 强制）</h3>
 * <p>幂等完全依赖数据库唯一索引 {@code pay_order.uk_merchant_order_no}：
 * 直接 INSERT，让数据库来裁决谁赢；输的一方捕获 {@link DuplicateKeyException}，回查已存在的那一行。
 * <b>禁止</b>「先 select 判断是否存在、不存在再 insert」的写法——两个线程可能同时 select 到 null，
 * 然后双双插入，等于没有幂等。</p>
 *
 * <h3>事务边界的划分</h3>
 * <p>本类把「捕获 DuplicateKeyException」放在<b>事务方法之外</b>的 {@link #createOrder} 里，
 * 由它调用真正带 {@code @Transactional} 的 {@link #doInsertNewOrder}。原因是 Spring 的事务回滚标记：
 * 当 INSERT 撞唯一索引时，MySQL/InnoDB 会把当前事务标记为回滚，Spring 的
 * {@code AbstractPlatformTransactionManager} 也会把该事务置为 rollback-only；
 * 如果此时还在同一个事务方法内部把异常吞掉并继续执行回查，提交阶段会抛
 * {@code UnexpectedRollbackException}（或把回查结果一起回滚掉）。
 * 把异常捕获挪到事务方法之外后，失败的那次插入事务干净地整体回滚，
 * 调用方随即开启一段新的、只读的自动提交连接去回查，不会被 rollback-only 污染。</p>
 *
 * <h3>为什么回查要重试</h3>
 * <p>见 {@link #FIND_RETRY_ATTEMPTS} 处的说明。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    /**
     * 撞唯一索引后回查的最大尝试次数。
     *
     * <p>输掉竞争的那一方拿到 DuplicateKeyException 时，赢家的事务<b>可能还没有提交</b>
     * （唯一索引的冲突检测发生在赢家插入语句执行时，而不是提交时）。
     * 因此此时回查完全可能合法地读到 null——这不是错误，只是「已存在但尚不可见」。
     * 这里用一个小的有界重试窗口覆盖赢家提交所需的极短时间：
     * 最多 {@code 20} 次，每次间隔 {@code 10ms}，即最多等待约 200ms。
     * 超过后再读不到才抛异常，说明赢家事务异常地长时间未提交（例如被长事务堵住），
     * 必须让它暴露出来而不是无限自旋。</p>
     */
    private static final int FIND_RETRY_ATTEMPTS = 20;

    /** 每次回查重试之间的休眠毫秒数，配合 {@link #FIND_RETRY_ATTEMPTS} 使用。 */
    private static final long FIND_RETRY_SLEEP_MILLIS = 10L;

    private final PayOrderMapper orderMapper;

    private final PayFlowMapper flowMapper;

    /**
     * 下单入口：并发/重复提交同一 merchantOrderNo 时返回同一个 orderId，且库里只有一行。
     *
     * <p><b>本方法刻意不加 {@code @Transactional}</b>，理由见类注释「事务边界的划分」。</p>
     *
     * @param request 下单请求，merchantOrderNo 与 amount 必须已经通过校验
     * @return 下单结果；{@code reused=true} 表示复用已存在订单，{@code false} 表示本次为插入方
     */
    public OrderCreateResponse createOrder(OrderCreateRequest request) {
        String merchantOrderNo = request.getMerchantOrderNo();

        try {
            // 竞争路径：直接插，让 uk_merchant_order_no 裁决胜负。
            PayOrder inserted = doInsertNewOrder(merchantOrderNo, request.getAmount());
            log.info("下单处理完成，merchantOrderNo={}, orderId={}, 本次插入成功",
                    merchantOrderNo, inserted.getId());
            return buildResponse(inserted, false);
        } catch (DuplicateKeyException e) {
            // 只捕获 DuplicateKeyException：它是「唯一索引冲突」这一种确定语义。
            // 绝不能写成 catch (Exception e)，否则真实数据库故障会被误判成「订单已存在」。
            PayOrder existing = findExistingWithRetry(merchantOrderNo);
            log.info("下单处理完成，merchantOrderNo={}, orderId={}, 撞唯一索引，返回已存在订单",
                    merchantOrderNo, existing.getId());
            return buildResponse(existing, true);
        }
    }

    /**
     * 真正落库的插入方法，带事务；只负责「插入成功」这条路径。
     *
     * <p>事务只覆盖「插 pay_order + 插 CREATE 流水」这两条写操作，
     * 不做任何回查，因此不存在「在 rollback-only 事务里继续读」的问题。</p>
     *
     * @param merchantOrderNo 商户订单号
     * @param amount          金额
     * @return 插入成功后的订单（自增主键已回填）
     */
    @Transactional(rollbackFor = Exception.class)
    protected PayOrder doInsertNewOrder(String merchantOrderNo, java.math.BigDecimal amount) {
        LocalDateTime now = LocalDateTime.now();

        PayOrder order = new PayOrder();
        order.setMerchantOrderNo(merchantOrderNo);
        order.setAmount(amount);
        order.setStatus(PayStatus.CREATED.name());
        order.setChannelTradeNo(null);
        order.setCreatedAt(now);
        order.setPaidAt(null);
        order.setUpdatedAt(now);

        // 此处若撞唯一索引会抛出 DuplicateKeyException，并让整个事务回滚。
        orderMapper.insert(order);

        // CREATE 流水只在「本次确实插入了订单」时写一条。
        // 如果输掉竞争的一方也补写一条 CREATE，并发场景下就会看到多条 CREATE 流水，
        // 直接污染「pay_order 恰好一行、pay_flow 恰好一条」这一唯一性证据。
        PayFlow flow = new PayFlow();
        flow.setOrderId(order.getId());
        flow.setEventType(EventType.CREATE.name());
        // FlowSource 只有 CALLBACK / QUERY / RECONCILE，没有 CREATE 专用来源；
        // 下单是回调侧的入口动作，统一记在 CALLBACK 来源下。
        flow.setSource(FlowSource.CALLBACK.name());
        flow.setFromStatus(null);
        flow.setToStatus(PayStatus.CREATED.name());
        flow.setRawPayload(null);
        flow.setCreatedAt(now);
        flowMapper.insert(flow);

        return order;
    }

    /**
     * 撞唯一索引后的有界回查：赢家可能尚未提交，因此短轮询等待其可见。
     *
     * @param merchantOrderNo 商户订单号
     * @return 已存在的订单，绝不为 null
     * @throws IllegalStateException 重试耗尽仍读不到时抛出
     */
    private PayOrder findExistingWithRetry(String merchantOrderNo) {
        for (int attempt = 1; attempt <= FIND_RETRY_ATTEMPTS; attempt++) {
            PayOrder existing = orderMapper.selectByMerchantOrderNo(merchantOrderNo);
            if (existing != null) {
                return existing;
            }
            if (attempt < FIND_RETRY_ATTEMPTS) {
                try {
                    Thread.sleep(FIND_RETRY_SLEEP_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "回查已存在订单时被中断，merchantOrderNo=" + merchantOrderNo, ie);
                }
            }
        }
        throw new IllegalStateException(
                "订单已存在（已撞唯一索引 uk_merchant_order_no）但重试 " + FIND_RETRY_ATTEMPTS
                        + " 次仍不可见，疑似赢家事务长时间未提交，merchantOrderNo=" + merchantOrderNo);
    }

    /**
     * 测试辅助查询：按商户订单号读取订单。
     *
     * @param merchantOrderNo 商户订单号
     * @return 订单；不存在返回 {@code null}
     */
    public PayOrder getOrderByNo(String merchantOrderNo) {
        return orderMapper.selectByMerchantOrderNo(merchantOrderNo);
    }

    /**
     * 组装响应。金额与状态一律取数据库中的那一行，保证重复请求回显的字段完全一致。
     *
     * @param order  数据库中的订单
     * @param reused 是否为复用已存在订单
     * @return 响应体
     */
    private OrderCreateResponse buildResponse(PayOrder order, boolean reused) {
        OrderCreateResponse response = new OrderCreateResponse();
        response.setOrderId(order.getId());
        response.setMerchantOrderNo(order.getMerchantOrderNo());
        response.setAmount(order.getAmount());
        response.setStatus(order.getStatus());
        response.setReused(reused);
        return response;
    }
}
