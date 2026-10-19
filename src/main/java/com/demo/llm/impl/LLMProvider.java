package com.demo.llm.impl;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

public enum LLMProvider {
    AUTO, OPENAI, ZHIPU, DEEPSEEK, DASHSCOPE, GEMINI, GENERIC;
    private static final Map<String, LLMProvider> HOSTS = Map.of(
            "api.openai.com", OPENAI, "open.bigmodel.cn", ZHIPU,
            "api.deepseek.com", DEEPSEEK, "dashscope.aliyuncs.com", DASHSCOPE,
            "dashscope-intl.aliyuncs.com", DASHSCOPE, "dashscope-us.aliyuncs.com", DASHSCOPE,
            "generativelanguage.googleapis.com", GEMINI);

    public static LLMProvider parse(String value) {
        try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid llm.provider"); }
    }
    public LLMProvider resolve(String url) {
        return this == AUTO ? HOSTS.getOrDefault(URI.create(url).getHost().toLowerCase(Locale.ROOT), GENERIC) : this;
    }
}
