package io.supportflow.tenant.mapper;

import io.supportflow.tenant.admin.dto.ReviewPageVO;
import io.supportflow.tenant.entity.EnterpriseReview;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * enterprise_review 企业审核表 Mapper。
 */
@Mapper
public interface EnterpriseReviewMapper extends BaseMapper<EnterpriseReview> {

    /**
     * 查询企业最新一条审核记录，SQL 见 EnterpriseReviewMapper.xml。
     */
    EnterpriseReview findLatestByEnterpriseId(@Param("enterpriseId") long enterpriseId);

    /**
     * 查询企业当前申请版本号（不存在时为 1）。
     */
    int nextVersionNo(@Param("enterpriseId") long enterpriseId);

    /**
     * 分页查询企业审核列表（SQL 见 EnterpriseReviewMapper.xml）。
     */
    IPage<ReviewPageVO> selectReviewAdminPage(
            Page<ReviewPageVO> page,
            @Param("status") Integer status,
            @Param("keyword") String keyword
    );
}
