package io.supportflow.billing.mapper;

import io.supportflow.billing.dto.PayMethodSumVO;
import io.supportflow.billing.dto.PaymentRowVO;
import io.supportflow.billing.entity.PaymentRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * payment_record 收款流水 Mapper，SQL 见 PaymentRecordMapper.xml。
 *
 * <p>只提供"写入 + 查询"：收款流水是事实，不改不删。</p>
 */
@Mapper
public interface PaymentRecordMapper extends BaseMapper<PaymentRecord> {

    /**
     * 落一条收款流水，同一笔业务重复就跳过（返回 1 = 新建、0 = 已存在）。
     *
     * <p>为什么不用"先查再插"：三方回调重推、平台重复点"确认收款"都可能有并发，
     * 交给唯一索引 `uk_payment_record_biz` 判断最省事——而且 PostgreSQL 里一旦违反约束，
     * 整个事务就进入 aborted 状态，catch 住异常也救不回来，所以走 `ON CONFLICT DO NOTHING`。</p>
     */
    int insertIgnore(@Param("record") PaymentRecord record);

    long countPayments(@Param("tenantCode") String tenantCode,
                       @Param("bizType") Integer bizType,
                       @Param("payMethodCode") Integer payMethodCode,
                       @Param("status") Integer status,
                       @Param("keyword") String keyword,
                       @Param("from") String from,
                       @Param("to") String to);

    List<Map<String, Object>> selectPayments(@Param("tenantCode") String tenantCode,
                                             @Param("bizType") Integer bizType,
                                             @Param("payMethodCode") Integer payMethodCode,
                                             @Param("status") Integer status,
                                             @Param("keyword") String keyword,
                                             @Param("from") String from,
                                             @Param("to") String to,
                                             @Param("limit") int limit,
                                             @Param("offset") long offset);

    /** 筛选范围内**成功收款**的合计（对账页顶部的"筛选结果合计"） */
    BigDecimal sumSucceeded(@Param("tenantCode") String tenantCode,
                                      @Param("bizType") Integer bizType,
                                      @Param("payMethodCode") Integer payMethodCode,
                                      @Param("status") Integer status,
                                      @Param("keyword") String keyword,
                                      @Param("from") String from,
                                      @Param("to") String to);

    /** 按时间口径的收款概览：累计 / 本月 / 今日（都只算成功的） */
    Map<String, Object> selectOverview();

    /** 按支付方式汇总（对账时看"钱从哪个渠道来"） */
    List<Map<String, Object>> selectByMethod(@Param("from") String from, @Param("to") String to);
}
