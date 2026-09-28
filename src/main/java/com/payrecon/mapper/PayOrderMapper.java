package com.payrecon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.payrecon.entity.PayOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * pay_order 的数据访问接口。
 *
 * <p>状态跃迁一律走「带 status 前置条件的 UPDATE」，由数据库返回影响行数，
 * 调用方据此判断是否抢到了这次跃迁，从而天然幂等。
 */
@Mapper
public interface PayOrderMapper extends BaseMapper<PayOrder> {

    /** 按商户订单号查询（uk_merchant_order_no 唯一）。 */
    @Select("SELECT id, merchant_order_no, amount, status, channel_trade_no, created_at, paid_at, updated_at "
          + "FROM pay_order WHERE merchant_order_no = #{merchantOrderNo}")
    PayOrder selectByMerchantOrderNo(@Param("merchantOrderNo") String merchantOrderNo);

    /**
     * 条件式状态推进：仅当订单仍处于非终态（CREATED/PAYING）时才更新，返回影响行数。
     *
     * <p>返回 0 表示「这次回调/查询没有抢到跃迁」，重复回调因此不会覆盖 paid_at。
     * {@code status IN ('CREATED','PAYING')} 是强制条件，不得放宽。
     */
    @Update("UPDATE pay_order SET status = #{toStatus}, channel_trade_no = #{channelTradeNo}, "
          + "paid_at = CASE WHEN #{toStatus} = 'SUCCESS' THEN NOW(3) ELSE paid_at END, updated_at = NOW(3) "
          + "WHERE merchant_order_no = #{merchantOrderNo} AND status IN ('CREATED','PAYING')")
    int advanceStatus(@Param("merchantOrderNo") String merchantOrderNo,
                      @Param("toStatus") String toStatus,
                      @Param("channelTradeNo") String channelTradeNo);

    /**
     * 测试辅助用：同样是条件式更新，终态订单（SUCCESS/FAILED/CLOSED）不会命中，
     * 因此对已成功订单执行非法跃迁会真实返回 0 行。
     */
    @Update("UPDATE pay_order SET status = #{toStatus}, updated_at = NOW(3) "
          + "WHERE merchant_order_no = #{no} AND status NOT IN ('SUCCESS','FAILED','CLOSED')")
    int forceUpdateStatus(@Param("no") String no, @Param("toStatus") String to);

    /** 查询长时间停留在 PAYING 的订单（主动查询兜底用）。 */
    @Select("SELECT id, merchant_order_no, amount, status, channel_trade_no, created_at, paid_at, updated_at "
          + "FROM pay_order WHERE status = 'PAYING' "
          + "AND updated_at < NOW(3) - INTERVAL #{minutes} MINUTE ORDER BY id LIMIT #{limit}")
    List<PayOrder> selectStalePaying(@Param("minutes") int minutes, @Param("limit") int limit);

    /** 查询某一账单日创建的订单（对账用）。 */
    @Select("SELECT id, merchant_order_no, amount, status, channel_trade_no, created_at, paid_at, updated_at "
          + "FROM pay_order WHERE DATE(created_at) = #{billDate} ORDER BY id")
    List<PayOrder> selectByCreatedDate(@Param("billDate") String billDate);
}
