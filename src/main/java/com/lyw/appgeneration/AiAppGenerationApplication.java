package com.lyw.appgeneration;

import dev.langchain4j.community.store.embedding.redis.spring.RedisEmbeddingStoreAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类
 *
 * @author lyw
 */
@MapperScan("com.lyw.appgeneration.mapper")
@SpringBootApplication(exclude = {RedisEmbeddingStoreAutoConfiguration.class})
public class AiAppGenerationApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiAppGenerationApplication.class, args);
    }

}
