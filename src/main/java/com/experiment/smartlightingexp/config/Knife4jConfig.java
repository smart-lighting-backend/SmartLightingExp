package com.experiment.smartlightingexp.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j 接口文档配置 — 启动后访问 /doc.html 查看 API 文档。
 */
@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("智慧照明系统 API")
                        .version("1.0.0")
                        .description("智能路灯实验项目后端接口文档")
                        .contact(new Contact()
                                .name("developer")));
    }
}
