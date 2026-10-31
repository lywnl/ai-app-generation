package com.lyw.appgeneration.service.impl;

/** 聊天与管理页使用相同的缓存键和失效边界。 */
final class UserMemoryCacheKeys {
    static final String CURRENT = "mem:pref:v3:";
    static final String PREVIOUS = "mem:pref:v2:";
    static final String LEGACY = "mem:pref:";
    private UserMemoryCacheKeys() { }
}
