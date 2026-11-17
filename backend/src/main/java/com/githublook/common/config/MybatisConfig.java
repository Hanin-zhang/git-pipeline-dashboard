package com.githublook.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Slf4j
@Configuration
@MapperScan("com.githublook.**.mapper")
public class MybatisConfig {

    private final DataSource dataSource;

    public MybatisConfig(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * The pagination dialect is detected from the live JDBC connection instead of being hard coded,
     * so the same build runs on the local H2 (MySQL mode) demo database and on real MySQL.
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        DbType dbType = detectDbType();
        log.info("MyBatis-Plus pagination dialect: {}", dbType);

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(dbType);
        pagination.setMaxLimit(200L);
        pagination.setOverflow(false);

        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    private DbType detectDbType() {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            return product != null && product.toUpperCase().contains("H2") ? DbType.H2 : DbType.MYSQL;
        } catch (SQLException e) {
            log.warn("Could not detect database product, falling back to MySQL pagination: {}", e.getMessage());
            return DbType.MYSQL;
        }
    }
}
