package com.demo.testing;

import com.demo.config.Config;

/** Baseline tests opt out explicitly; memory acceptance tests use isolated, enabled harnesses. */
public final class TestConfigs {
    private TestConfigs() { }
    public static Config defaults() { return withoutMemory(Config.defaults()); }
    public static Config withoutMemory(Config config) { return config.withMemory(config.getMemory().withEnabled(false)); }
}
