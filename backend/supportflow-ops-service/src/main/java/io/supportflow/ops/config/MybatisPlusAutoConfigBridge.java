package io.supportflow.ops.config;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;

/**
 * MyBatis-Plus 装配兜底（Spring Boot 4 兼容，与 tenant/billing 同一套方案）。
 *
 * <p>Boot 4 下 MyBatis-Plus starter 的自动配置不自动生效，结果 `SqlSessionFactory` 没建、
 * Mapper 也不注册，启动直接报 "required a bean of type 'xxxMapper' that could not be found"。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnMissingBean(SqlSessionFactory.class)
public class MybatisPlusAutoConfigBridge {

    @Bean
    public SqlSessionFactory sqlSessionFactory(
            DataSource dataSource,
            @Qualifier("mybatisPlusInterceptor") MybatisPlusInterceptor mybatisPlusInterceptor
    ) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/mapper/**/*.xml"));

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);

        GlobalConfig globalConfig = new GlobalConfig();
        GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
        dbConfig.setIdType(IdType.INPUT);
        globalConfig.setDbConfig(dbConfig);
        factory.setGlobalConfig(globalConfig);

        if (mybatisPlusInterceptor != null) {
            factory.setPlugins(mybatisPlusInterceptor);
        }
        return factory.getObject();
    }

    @Bean
    public SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }
}
