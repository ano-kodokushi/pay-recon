package com.payrecon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.payrecon.entity.PayFlow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * pay_flow 的数据访问接口。
 *
 * <p>该表只追加：本接口不暴露任何 UPDATE / DELETE 方法。
 */
@Mapper
public interface PayFlowMapper extends BaseMapper<PayFlow> {

    /** 追加一条流水并回填自增主键。 */
    @Insert("INSERT INTO pay_flow (order_id, event_type, `source`, from_status, to_status, raw_payload, created_at) "
          + "VALUES (#{orderId}, #{eventType}, #{source}, #{fromStatus}, #{toStatus}, #{rawPayload}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertReturningId(PayFlow payFlow);

    /** 按订单查询流水，按时间正序。 */
    @Select("SELECT id, order_id, event_type, `source`, from_status, to_status, raw_payload, created_at "
          + "FROM pay_flow WHERE order_id = #{orderId} ORDER BY id")
    List<PayFlow> selectByOrderId(@Param("orderId") Long orderId);
}
