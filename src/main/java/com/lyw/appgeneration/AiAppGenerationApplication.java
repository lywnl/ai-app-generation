package com.lyw.appgeneration;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类
 *
 * @author lyw
 */
@SpringBootApplication
@MapperScan("com.lyw.appgeneration.mapper")
public class AiAppGenerationApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiAppGenerationApplication.class, args);
    }

}
