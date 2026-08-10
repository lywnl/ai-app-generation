package com.lyw.appgeneration.rag.build;

/**
 * 为每次真实 Vue 生成分配独立的运行 appId。
 */
@FunctionalInterface
interface VueGenerationAppIdAllocator {

    long nextAppId();
}
