package com.demo.config;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShellConfigurationTest {
    @Test void hostProfilesAndAliasesAreFrozenAndNotInheritedFromApplicationEnvironment() {
        String absolute=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        var shell=new HashMap<String,Object>();
        shell.put("aliases",Map.of("java",absolute));
        shell.put("profiles",Map.of("java",Map.of("environment",Map.of("JAVA_HOME","fixed-toolchain"),"credentials",false)));
        Config config=ConfigLoader.from(Map.of("tools",Map.of("shell",shell)),Map.of("FAKE_API_KEY","never-inherit"));
        shell.clear();
        assertEquals(Path.of(absolute),config.getShellPolicy().aliases().get("java"));
        assertEquals("fixed-toolchain",config.getShellPolicy().profiles().get("java").values().get("JAVA_HOME"));
        assertFalse(config.getShellPolicy().profiles().get("default").values().containsKey("FAKE_API_KEY"));
        assertThrows(UnsupportedOperationException.class,()->config.getShellPolicy().profiles().clear());
    }
    @Test void malformedHostSettingsFailStartup() {
        for(Object shell:List.of("wrong",Map.of("aliases",Map.of("java","relative.exe")),
                Map.of("search_directories",List.of(".")),Map.of("unknown",true),
                Map.of("profiles",Map.of("dev",Map.of("environment",Map.of("BASH_ENV","inject")))))) {
            assertThrows(IllegalArgumentException.class,()->ConfigLoader.from(Map.of("tools",Map.of("shell",shell)),Map.of()));
        }
    }
}
