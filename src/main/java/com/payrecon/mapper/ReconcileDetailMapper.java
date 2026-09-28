package com.payrecon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.payrecon.entity.ReconcileDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * reconcile_detail 的数据访问接口。
 */
@Mapper
public interface ReconcileDetailMapper extends BaseMapper<ReconcileDetail> {

    /** 按账单日期查询差异明细。 */
    @Select("SELECT id, order_id, merchant_order_no, bill_date, local_status, channel_status, "
          + "diff_type, resolved, created_at "
          + "FROM reconcile_detail WHERE bill_date = #{billDate} ORDER BY id")
    List<ReconcileDetail> selectByBillDate(@Param("billDate") String billDate);

    /**
     * 把一条差异明细标记为已处理/未处理。
     *
     * <p>这是对账补偿唯一允许的库内写操作：只翻转 {@code resolved} 标志位，
     * <b>绝不修改 {@code pay_order.status}</b>——状态推进一律走
     * {@code AdvanceService.advance(...)}。
     *
     * <p>差异明细是审计证据，因此只有 UPDATE，没有 DELETE。
     *
     * @param id       明细主键
     * @param resolved 1 表示已解决（补偿成功或订单已是成功态），0 表示仍待人工处理
     * @return 影响行数
     */
    @Update("UPDATE reconcile_detail SET resolved = #{resolved} WHERE id = #{id}")
    int markResolved(@Param("id") Long id, @Param("resolved") Integer resolved);
}
