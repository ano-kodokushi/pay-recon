package com.payrecon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.payrecon.entity.AccountEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * account_entry 的数据访问接口。
 *
 * <p>uk_order_id 保证同一订单只能入账一次；countByOrderId 是「入账只执行 1 次」的硬断言。
 */
@Mapper
public interface AccountEntryMapper extends BaseMapper<AccountEntry> {

    /** 统计某订单的入账条数，正确值恒为 0 或 1。 */
    @Select("SELECT COUNT(*) FROM account_entry WHERE order_id = #{orderId}")
    int countByOrderId(@Param("orderId") Long orderId);

    /** 按订单查询入账流水。 */
    @Select("SELECT id, order_id, amount, entry_type, created_at "
          + "FROM account_entry WHERE order_id = #{orderId} ORDER BY id")
    List<AccountEntry> selectByOrderId(@Param("orderId") Long orderId);
}
