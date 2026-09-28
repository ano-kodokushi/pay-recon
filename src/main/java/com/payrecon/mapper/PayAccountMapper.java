package com.payrecon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.payrecon.entity.PayAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * pay_account 的数据访问接口。
 */
@Mapper
public interface PayAccountMapper extends BaseMapper<PayAccount> {

    /** 按账户号查询（uk_account_no 唯一）。 */
    @Select("SELECT id, account_no, balance, updated_at FROM pay_account WHERE account_no = #{accountNo}")
    PayAccount selectByAccountNo(@Param("accountNo") String accountNo);

    /** 余额原子自增，返回影响行数。金额用 BigDecimal，禁止浮点。 */
    @Update("UPDATE pay_account SET balance = balance + #{amount}, updated_at = #{now} "
          + "WHERE account_no = #{accountNo}")
    int increaseBalance(@Param("accountNo") String accountNo,
                        @Param("amount") BigDecimal amount,
                        @Param("now") LocalDateTime now);
}
