package com.demo.tools.process;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CommandPlannerTest {
    @TempDir Path root;
    String request(Path executable, String... args) {
        var o=new JsonObject();o.addProperty("mode","exec");o.addProperty("executable",executable.toString());o.add("args",new Gson().toJsonTree(args));return o.toString();
    }
    @Test void changedEntryAndRecreatedCwdInvalidatePlan()throws Exception {
        Path original=ShellPolicyConfig.windows()?Path.of(System.getenv("SystemRoot"),"System32","whoami.exe"):Path.of("/usr/bin/true");
        Path entry=Files.copy(original,root.resolve(ShellPolicyConfig.windows()?"entry.exe":"entry"));
        if(!ShellPolicyConfig.windows())Files.setPosixFilePermissions(entry,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        var planner=new CommandPlanner(root,ShellPolicyConfig.defaults());var plan=planner.prepare(request(entry));assertTrue(planner.valid(plan));
        Files.write(entry,new byte[]{1},StandardOpenOption.APPEND);assertFalse(planner.valid(plan));
        Files.createDirectory(root.resolve("sub"));
        var o=new JsonObject();o.addProperty("mode","script");o.addProperty("script","echo hello");o.addProperty("cwd","sub");
        var directoryPlan=planner.prepare(o.toString());Files.move(root.resolve("sub"),root.resolve("old"));Files.createDirectory(root.resolve("sub"));
        assertFalse(planner.valid(directoryPlan));
    }
    @Test void workspaceImplicitSearchIsExcludedAndTraversalCwdFails()throws Exception {
        var defaults=ShellPolicyConfig.defaults();
        var config=new ShellPolicyConfig(Map.of(),List.of(root),defaults.profiles(),defaults.shells(),defaults.version());
        Files.writeString(root.resolve(ShellPolicyConfig.windows()?"shadow.exe":"shadow"),"untrusted");
        var planner=new CommandPlanner(root,config);
        assertThrows(IllegalArgumentException.class,()->planner.prepare(request(Path.of("shadow"))));
        assertThrows(IllegalArgumentException.class,()->planner.prepare("{\"mode\":\"script\",\"script\":\"echo x\",\"cwd\":\"..\"}"));
    }
    @Test void nulAndMalformedUnicodeNeverReachNativeLauncher() {
        var planner=new CommandPlanner(root,ShellPolicyConfig.defaults());
        for(String bad:List.of("\u0000","\ud800","\udc00")) {
            var o=new JsonObject();o.addProperty("mode","script");o.addProperty("script",bad);
            assertThrows(IllegalArgumentException.class,()->planner.prepare(o.toString()));
        }
    }
    @Test void canonicalGrantKeyIncludesExactScriptTimeoutAndPolicyVersion()throws Exception {
        var defaults=ShellPolicyConfig.defaults();var planner=new CommandPlanner(root,defaults);
        var first=planner.prepare("{\"mode\":\"script\",\"script\":\"echo hello\"}");
        assertEquals(first.authorizationKey(),planner.prepare("{\"script\":\"echo hello\",\"mode\":\"script\"}").authorizationKey());
        assertNotEquals(first.authorizationKey(),planner.prepare("{\"mode\":\"script\",\"script\":\"echo  hello\"}").authorizationKey());
        assertNotEquals(first.authorizationKey(),planner.prepare("{\"mode\":\"script\",\"script\":\"echo hello\",\"timeout_ms\":10000}").authorizationKey());
        var changed=new ShellPolicyConfig(defaults.aliases(),defaults.searchDirectories(),defaults.profiles(),defaults.shells(),"changed-policy");
        assertNotEquals(first.authorizationKey(),new CommandPlanner(root,changed).prepare("{\"mode\":\"script\",\"script\":\"echo hello\"}").authorizationKey());
    }
}
