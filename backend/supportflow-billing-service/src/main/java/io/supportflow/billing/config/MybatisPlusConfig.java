package io.supportflow.billing.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** MyBatis-Plus 配置：分页 + 防全表更新/删除（与 tenant-service / customer-service 同一套接入方案）。 */
@Configuration
public class MybatisPlusConfig {

    private static final Logger log = LoggerFactory.getLogger(MybatisPlusConfig.class);

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
        pagination.setMaxLimit(500L);
        pagination.setOverflow(true);
        interceptor.addInnerInterceptor(pagination);

        // 防全表更新/删除：账单、订单这类"钱相关"的表，误操作代价太大
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        log.info("MyBatis-Plus 拦截器已配置：分页(max=500)、全表操作拦截");
        return interceptor;
    }
}
