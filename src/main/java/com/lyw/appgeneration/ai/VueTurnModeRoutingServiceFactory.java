package com.lyw.appgeneration.ai;

import com.lyw.appgeneration.utils.SpringContextUtil;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Vue 回合模式路由服务工厂。 */
@Configuration
public class VueTurnModeRoutingServiceFactory {

    /** 每次创建独立服务，避免并发回合共享路由服务状态。 */
    public VueTurnModeRoutingService create() {
        ChatModel chatModel = SpringContextUtil.getBean(
                "routingChatModelPrototype", ChatModel.class);
        return AiServices.builder(VueTurnModeRoutingService.class)
                .chatModel(chatModel)
                .build();
    }

    @Bean
    public VueTurnModeRoutingService vueTurnModeRoutingService() {
        return create();
    }
}
