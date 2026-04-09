package com.lyw.appgeneration.core;

import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AiCodeGeneratorFacadeTest {

    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;

    @Test
    void generateAndSaveCode() {
//        File file = aiCodeGeneratorFacade.generateAndSaveCode("给我生成一个博客网站20行以内", CodeGenTypeEnum.HTML);
        File file = aiCodeGeneratorFacade.generateAndSaveCode("给我生成一个博客网站20行以内", CodeGenTypeEnum.MULTI_FILE);
        assertNotNull(file);
    }

    @Test
    void generateAndSaveCodeStream() {
        Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream("给我生成一个博客网站20行以内", CodeGenTypeEnum.MULTI_FILE);
        //阻塞等待所有数据收集完成
        List<String> result = codeStream.collectList().block();
        //验证结果
        Assertions.assertNotNull(result);
        //拼接字符串 得到完整数据
        String fullCode = String.join("", result);
        Assertions.assertFalse(fullCode.isEmpty());
    }
}