package com.lyw.appgeneration.ai;

import com.lyw.appgeneration.ai.model.HtmlCodeResult;
import com.lyw.appgeneration.ai.model.MultiFileCodeResult;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "EXTERNAL_INTEGRATION_TESTS", matches = "true")
class AiCodeGeneratorServiceTest {

    @Resource
    private AiGeneratorServiceFactory aiGeneratorServiceFactory;

    @Test
    void generateHtmlCode() {
        AiCodeGeneratorService aiCodeGeneratorService = aiGeneratorServiceFactory
                .getAiCodeGeneratorService(1L, CodeGenTypeEnum.HTML);
        HtmlCodeResult result = aiCodeGeneratorService.generateHtmlCode("生成一个博客页面二十行以内");
        Assertions.assertNotNull(result);
    }

    @Test
    void generateMultiFileCode() {
        AiCodeGeneratorService aiCodeGeneratorService = aiGeneratorServiceFactory
                .getAiCodeGeneratorService(1L, CodeGenTypeEnum.MULTI_FILE);
        MultiFileCodeResult result = aiCodeGeneratorService.generateMultiFileCode("生成一个博客页面50行以内");
        Assertions.assertNotNull(result);
    }

}
