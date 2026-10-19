package com.demo.tools.process;

import com.demo.tools.*;
import com.demo.tools.builtin.ShellRunTool;
import com.demo.execution.CancellationToken;
import com.demo.model.ToolResult;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ShellEndToEndTest {
    @TempDir Path root;
    ToolResult approved(JsonObject request,ShellPolicyConfig config)throws Exception {
        var coordinator=new ApprovalCoordinator(true,e->{});var pool=Executors.newSingleThreadExecutor();
        try {
            var waiting=pool.submit(()->new ShellRunTool(root,config).execute(request.toString(),
                    new ToolExecutionContext(CancellationToken.none(),coordinator,root.resolve("output"),s->{})));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(coordinator.pending().isEmpty()&&!waiting.isDone()&&System.nanoTime()<deadline)Thread.sleep(5);
            var pending=coordinator.pending().orElseThrow();assertTrue(coordinator.submit(pending.generation(),ApprovalCoordinator.Decision.ONCE));
            return waiting.get(120,TimeUnit.SECONDS);
        }finally{coordinator.close();pool.shutdownNow();}
    }
    @Test void gitExecWithExplicitHostAlias()throws Exception {
        Path git=ShellPolicyConfig.windows()?Path.of("C:/Program Files/Git/cmd/git.exe"):Path.of("/usr/bin/git");
        Assumptions.assumeTrue(Files.isRegularFile(git),"Git unavailable on this host");
        var defaults=ShellPolicyConfig.defaults();
        var config=new ShellPolicyConfig(Map.of("git",git),defaults.searchDirectories(),defaults.profiles(),defaults.shells(),defaults.version());
        var init=new JsonObject();init.addProperty("mode","exec");init.addProperty("executable","git");init.add("args",new Gson().toJsonTree(List.of("init","--quiet")));
        assertTrue(approved(init,config).isSuccess());
        init.add("args",new Gson().toJsonTree(List.of("status","--short")));
        var result=approved(init,config);assertTrue(result.isSuccess(),result.getOutput());
    }
    @Test void realGradleWrapperRunsForegroundTestTask()throws Exception {
        Path project=Path.of(System.getProperty("user.dir"));
        Files.createDirectories(root.resolve("gradle/wrapper"));
        for(String file:List.of("gradlew","gradlew.bat","gradle/wrapper/gradle-wrapper.jar","gradle/wrapper/gradle-wrapper.properties"))
            Files.copy(project.resolve(file),root.resolve(file));
        if(!ShellPolicyConfig.windows())Files.setPosixFilePermissions(root.resolve("gradlew"),java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.writeString(root.resolve("settings.gradle"),"rootProject.name='approval-fixture'\n");
        Files.writeString(root.resolve("build.gradle"),"tasks.register('test') { doLast { assert 1 + 1 == 2; println 'APPROVED_BUILD_OK' } }\n");
        var defaults=ShellPolicyConfig.defaults();var env=new HashMap<>(defaults.profiles().get("default").values());
        env.put("JAVA_HOME",System.getProperty("java.home"));
        env.put("PATH",Path.of(System.getProperty("java.home"),"bin")+java.io.File.pathSeparator+env.get("PATH"));
        String cache=System.getProperty("coding.agent.gradle.cache",Path.of(System.getProperty("user.home"),".gradle").toString());
        env.put("GRADLE_USER_HOME",cache);
        var config=new ShellPolicyConfig(Map.of(),defaults.searchDirectories(),Map.of("java",new ShellPolicyConfig.EnvironmentProfile(env,false)),defaults.shells(),defaults.version());
        var request=new JsonObject();request.addProperty("mode",ShellPolicyConfig.windows()?"script":"exec");
        if(ShellPolicyConfig.windows())request.addProperty("script","& .\\gradlew.bat --offline --no-daemon test; exit $LASTEXITCODE");
        else {request.addProperty("executable","./gradlew");request.add("args",new Gson().toJsonTree(List.of("--offline","--no-daemon","test")));}
        request.addProperty("environment_profile","java");request.addProperty("timeout_ms",120000);
        var result=approved(request,config);assertTrue(result.isSuccess(),result.getOutput());assertTrue(result.getOutput().contains("APPROVED_BUILD_OK"),result.getOutput());
    }
    @Test void windowsBatchActuallyRunsAndPreservesExitCode()throws Exception {
        Assumptions.assumeTrue(ShellPolicyConfig.windows(),"Windows batch behavior");
        Files.writeString(root.resolve("probe.bat"),"@echo off\r\necho BATCH_EXECUTED\r\nexit /b 7\r\n");
        var request=new JsonObject();request.addProperty("mode","script");
        request.addProperty("script","& .\\probe.bat; exit $LASTEXITCODE");
        var result=approved(request,ShellPolicyConfig.defaults());assertFalse(result.isSuccess());
        var data=JsonParser.parseString(result.getOutput()).getAsJsonObject();
        assertEquals(7,data.get("exit_code").getAsInt());assertTrue(data.get("output").getAsString().contains("BATCH_EXECUTED"));
    }
    @Test void explicitCredentialProfileIsRedactedInResultsEventsAndArtifacts()throws Exception {
        String secret="shell-profile-secret-unique-abcdef012345";
        var defaults=ShellPolicyConfig.defaults();var env=new HashMap<>(defaults.profiles().get("default").values());
        env.put("TEST_SECRET",secret);
        var config=new ShellPolicyConfig(Map.of(),defaults.searchDirectories(),Map.of("credential",new ShellPolicyConfig.EnvironmentProfile(env,true)),defaults.shells(),defaults.version());
        var request=new JsonObject();request.addProperty("mode","script");request.addProperty("environment_profile","credential");
        request.addProperty("script",ShellPolicyConfig.windows()?"Write-Output $env:TEST_SECRET":"printf '%s\\n' \"$TEST_SECRET\"");
        var result=approved(request,config);assertTrue(result.isSuccess(),result.getOutput());assertFalse(result.getOutput().contains(secret));assertTrue(result.getOutput().contains("[redacted]"));
        var data=JsonParser.parseString(result.getOutput()).getAsJsonObject();
        assertFalse(Files.readString(Path.of(data.get("output_file").getAsString())).contains(secret));
    }
}
