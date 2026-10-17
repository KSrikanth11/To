package com.example.recipient.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/** Creates a database connection only when MySQL is the selected recipient source. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "resolver.source", havingValue = "mysql")
public class RecipientMysqlConfiguration {
    @Bean
    DataSource recipientDataSource(@Value("${spring.datasource.url}") String url,
                                   @Value("${spring.datasource.username:}") String username,
                                   @Value("${spring.datasource.password:}") String password) {
        HikariDataSource dataSource = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName("com.mysql.cj.jdbc.Driver")
                .url(url)
                .username(username)
                .password(password)
                .build();
        dataSource.setPoolName("recipient-resolver-pool");
        dataSource.setMaximumPoolSize(5);
        dataSource.setMinimumIdle(1);
        dataSource.setReadOnly(true);
        dataSource.setConnectionTimeout(5000);
        return dataSource;
    }

    @Bean
    JdbcTemplate recipientJdbcTemplate(DataSource recipientDataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(recipientDataSource);
        jdbc.setFetchSize(500);
        jdbc.setQueryTimeout(20);
        return jdbc;
    }
}
