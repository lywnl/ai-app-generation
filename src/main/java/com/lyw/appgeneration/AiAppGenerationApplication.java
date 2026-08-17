package com.lyw.appgeneration;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * 启动类
 *
 * @author lyw
 */
@EnableCaching
@MapperScan("com.lyw.appgeneration.mapper")
@SpringBootApplication
public class AiAppGenerationApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiAppGenerationApplication.class, args);
    }

}
