package com.bjtu.simulation.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import org.springframework.context.annotation.Configuration;

/**
 * 报告序列化 mapper 工厂。不注册成 Spring Bean(否则会触发
 * {@code JacksonAutoConfiguration#ConditionalOnMissingBean(ObjectMapper.class)}
 * 关闭默认 mapper,导致 Spring MVC 用 snake_case 反序列化 camelCase 入参 → HTTP 400)。
 * 业务层通过 {@link #createReportObjectMapper()} 静态工厂获取 mapper,
 * Spring Boot 默认 ObjectMapper 仍负责 HTTP 入参 / 出参的 camelCase 序列化。
 */
@Configuration
public class AppBeansConfig {

    public static ObjectMapper createReportObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        return mapper;
    }
}
