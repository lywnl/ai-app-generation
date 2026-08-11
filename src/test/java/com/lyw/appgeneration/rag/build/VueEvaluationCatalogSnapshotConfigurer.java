package com.lyw.appgeneration.rag.build;

import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.retrieval.VueRetrievalResourceProvider;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;

import java.util.Objects;

/**
 * 在组件扫描完成后用本轮已核验目录替换生成 Spring 的检索资源定义。
 */
final class VueEvaluationCatalogSnapshotConfigurer implements BeanFactoryPostProcessor {

    private static final String PROVIDER_BEAN_NAME = "vueRetrievalResourceProvider";
    private final TemplateCatalog catalog;

    VueEvaluationCatalogSnapshotConfigurer(TemplateCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "Vue 知识目录不能为空");
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
            throws BeansException {
        if (!(beanFactory instanceof BeanDefinitionRegistry registry)) {
            throw new IllegalStateException("当前 BeanFactory 不支持替换 Vue 检索资源定义");
        }
        if (registry.containsBeanDefinition(PROVIDER_BEAN_NAME)) {
            registry.removeBeanDefinition(PROVIDER_BEAN_NAME);
        }
        RootBeanDefinition definition = new RootBeanDefinition(
                VueRetrievalResourceProvider.class,
                () -> VueRetrievalResourceProvider.forEvaluation(catalog));
        definition.setDestroyMethodName("close");
        registry.registerBeanDefinition(PROVIDER_BEAN_NAME, definition);
    }
}
