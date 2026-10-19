package com.demo.tools.process;
import com.demo.execution.*;
import com.demo.tools.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class ShellExecutorTest {
 @TempDir Path root;
 static ToolExecutionContext approvedContext(ExecutionPlan plan) {
  var holder=new java.util.concurrent.atomic.AtomicReference<ApprovalCoordinator>();
  var coordinator=new ApprovalCoordinator(true,event->{if(event.kind().equals("requested"))holder.get().submit(event.approval().generation(),ApprovalCoordinator.Decision.ONCE);});holder.set(coordinator);
  var context=new ToolExecutionContext(CancellationToken.none(),coordinator,null,text->{});
  if(!coordinator.authorize(plan,context.token()))throw new AssertionError("Test approval denied");return context;
 }
 String script(String script){var o=new JsonObject();o.addProperty("mode","script");o.addProperty("script",script);return o.toString();}
 @Test void environmentAndLaunchOptionsAreFrozen()throws Exception {
  var config=ShellPolicyConfig.defaults();var planner=new CommandPlanner(root,config);var plan=planner.prepare(script("echo hello"));
  assertTrue(plan.executable().isAbsolute());
  assertFalse(plan.environment().containsKey("LLM_API_KEY"));assertFalse(plan.environment().containsKey("BASH_ENV"));assertFalse(plan.environment().containsKey("HOME"));
  assertThrows(UnsupportedOperationException.class,()->plan.environment().put("X","y"));
  assertEquals(ShellPolicy.PolicyDecision.REQUIRE_APPROVAL,new ShellPolicy().evaluate(plan,config));
  if(ShellPolicyConfig.windows())assertTrue(plan.commandLine().containsAll(List.of("-NoProfile","-NonInteractive","-EncodedCommand")));
  else assertEquals(List.of("-c","echo hello"),plan.commandLine().subList(1,3));
  assertTrue(planner.valid(plan));
 }
 @Test void directArgumentsAreNotInterpreted()throws Exception {
  String javaPath=Path.of(System.getProperty("java.home"),"bin",ShellPolicyConfig.windows()?"java.exe":"java").toString();
  String classes=Path.of(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
  List<String> values=List.of("", "a b", "a\\\"b", "中文", "$HOME", "%PATH%", "x|y", "*", "trailing\\");
  var o=new JsonObject();o.addProperty("mode","exec");o.addProperty("executable",javaPath);
  var args=new ArrayList<>(List.of("-cp",classes,Child.class.getName()));
  if(ShellPolicyConfig.windows()) {
   Path source=root.resolve("Echo.cs"),target=root.resolve("Echo.exe");
   Files.writeString(source,"public class Echo {public static void Main(string[] args){System.Console.WriteLine(System.Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes(string.Join(\"\\u0001\",args))));}}",java.nio.charset.StandardCharsets.UTF_8);
   Path compiler=Path.of(System.getenv("SystemRoot"),"Microsoft.NET","Framework64","v4.0.30319","csc.exe");
   Process compile=new ProcessBuilder(compiler.toString(),"/nologo","/out:"+target,source.toString()).redirectErrorStream(true).start();
   String diagnostic=new String(compile.getInputStream().readAllBytes());assertEquals(0,compile.waitFor(),diagnostic);
   o.addProperty("executable",target.toString());args.clear();
  }
  args.addAll(values);o.add("args",new Gson().toJsonTree(args));
  var plan=new CommandPlanner(root,ShellPolicyConfig.defaults()).prepare(o.toString());
  var result=new ShellExecutor().execute(plan,approvedContext(plan));
  assertTrue(result.isSuccess(),result.getOutput());
  String output=JsonParser.parseString(result.getOutput()).getAsJsonObject().get("output").getAsString().strip();
  String expected=Base64.getEncoder().encodeToString(String.join("\u0001",values).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  assertEquals(expected,output);
 }
 @Test void badLimitsAndShellBypassRejected()throws Exception {
  var planner=new CommandPlanner(root,ShellPolicyConfig.defaults());
  assertThrows(IllegalArgumentException.class,()->planner.prepare("{\"mode\":\"script\",\"script\":\"echo x\",\"timeout_ms\":0}"));
  String shell=ShellPolicyConfig.windows()?"cmd.exe":"/bin/sh";
  var o=new JsonObject();o.addProperty("mode","exec");o.addProperty("executable",shell);
  assertTrue(assertThrows(IllegalArgumentException.class,()->planner.prepare(o.toString())).getMessage().contains("SCRIPT_MODE_REQUIRED"));
 }
 @Test void parentProcessSecretDoesNotReachExecutedProgram()throws Exception {
  String classes=Path.of(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
  String cp=classes+java.io.File.pathSeparator+System.getProperty("coding.agent.test.classpath");
  ProcessBuilder child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",cp,Child.class.getName(),"secret-check",root.toString()).redirectErrorStream(true);
  child.environment().put("CODING_AGENT_SIMULATED_SECRET","FAKE_SECRET_MUST_NOT_ESCAPE");
  Process process=child.start();String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
  assertEquals(0,process.waitFor(),output);assertTrue(output.contains("checked"),output);assertFalse(output.contains("FAKE_SECRET_MUST_NOT_ESCAPE"),output);
 }
 @Test void publicExecutorCannotBypassApprovalOrReplayOncePermit()throws Exception {
  var plan=new CommandPlanner(root,ShellPolicyConfig.defaults()).prepare(script("echo authorized"));
  var executor=new ShellExecutor();
  assertEquals("EXECUTION_NOT_AUTHORIZED",assertThrows(ShellExecutionException.class,()->executor.execute(plan,ToolExecutionContext.unavailable(CancellationToken.none()))).getMessage());
  var context=approvedContext(plan);assertTrue(executor.execute(plan,context).isSuccess());
  assertEquals("EXECUTION_NOT_AUTHORIZED",assertThrows(ShellExecutionException.class,()->executor.execute(plan,context)).getMessage());
 }
 @Test void publicExecutorRechecksAnAlreadyApprovedEntry()throws Exception {
  Path original=ShellPolicyConfig.windows()?Path.of(System.getenv("SystemRoot"),"System32","whoami.exe"):Path.of("/usr/bin/true");
  Path entry=Files.copy(original,root.resolve(ShellPolicyConfig.windows()?"entry.exe":"entry"));
  if(!ShellPolicyConfig.windows())Files.setPosixFilePermissions(entry,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
  var request=new JsonObject();request.addProperty("mode","exec");request.addProperty("executable",entry.toString());
  var plan=new CommandPlanner(root,ShellPolicyConfig.defaults()).prepare(request.toString());var context=approvedContext(plan);
  Files.write(entry,new byte[]{1},StandardOpenOption.APPEND);
  var failure=assertThrows(ShellExecutionException.class,()->new ShellExecutor().execute(plan,context));
  assertEquals("PLAN_INVALIDATED",failure.getMessage());assertFalse(failure.started());
 }
 public static class Child {public static void main(String[] args)throws Exception{
  if(args.length>0 && args[0].equals("secret-check")) {
   if(!"FAKE_SECRET_MUST_NOT_ESCAPE".equals(System.getenv("CODING_AGENT_SIMULATED_SECRET")))throw new AssertionError();
   String script=ShellPolicyConfig.windows()?"Write-Output ('secret=' + $env:CODING_AGENT_SIMULATED_SECRET)":"echo secret=$CODING_AGENT_SIMULATED_SECRET";
   var request=new JsonObject();request.addProperty("mode","script");request.addProperty("script",script);
   var plan=new CommandPlanner(Path.of(args[1]),ShellPolicyConfig.defaults()).prepare(request.toString());
   if(plan.environment().containsKey("CODING_AGENT_SIMULATED_SECRET"))throw new AssertionError();
   var result=new ShellExecutor().execute(plan,approvedContext(plan));
   if(!result.isSuccess() || result.getOutput().contains("FAKE_SECRET_MUST_NOT_ESCAPE"))throw new AssertionError();
   System.out.println("checked");return;
  }

  System.out.println(Base64.getEncoder().encodeToString(String.join("\u0001",args).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
 }}
}
