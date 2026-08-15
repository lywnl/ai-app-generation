package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.config.AppCodeDeployProperties;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.vo.app.AppVO;
import com.lyw.appgeneration.service.AppDeployUrlBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AppServiceAppVoTest {

    private final AppDeployUrlBuilder deployUrlBuilder =
            deployUrlBuilder();
    private AppServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AppServiceImpl();
        ReflectionTestUtils.setField(
                service, "appDeployUrlBuilder", deployUrlBuilder);
    }

    @Test
    void 已部署应用视图必须包含后端生成的完整URL() {
        App app = App.builder().id(7L).deployKey("deploy7").build();

        AppVO appVO = service.getAppVO(app);

        assertEquals("deploy7", appVO.getDeployKey());
        assertEquals("https://example.com/deploy7/", appVO.getDeployUrl());
    }

    @Test
    void 未部署应用视图的完整URL必须为空() {
        App app = App.builder().id(7L).build();

        AppVO appVO = service.getAppVO(app);

        assertNull(appVO.getDeployUrl());
    }

    private static AppDeployUrlBuilder deployUrlBuilder() {
        AppCodeDeployProperties properties = new AppCodeDeployProperties();
        properties.setBaseUrl("https://example.com");
        properties.afterPropertiesSet();
        return new AppDeployUrlBuilder(properties);
    }
}
