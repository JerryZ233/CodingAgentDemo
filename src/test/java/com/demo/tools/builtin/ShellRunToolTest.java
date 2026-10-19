package com.demo.tools.builtin;
import com.demo.tools.*;
import com.demo.tools.process.*;
import com.demo.execution.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class ShellRunToolTest {
 @TempDir Path root;
 private String request(String script){JsonObject a=new JsonObject();a.addProperty("mode","script");a.addProperty("script",script);return a.toString();}
 @Test void legacyAndInvalidFieldsNeverExecute() {
  var tool=new ShellRunTool(root);
  assertTrue(tool.execute("{\"command\":\"echo no\"}").getOutput().contains("LEGACY_COMMAND_UNSUPPORTED"));
  for(String args:List.of("{\"mode\":\"exec\",\"executable\":\"java\",\"script\":\"x\"}","{\"mode\":\"script\",\"script\":\"x\",\"extra\":1}","{\"mode\":\"exec\",\"executable\":\"java\",\"args\":[null]}"))assertFalse(tool.execute(args).isSuccess());
 }
 @Test void noChannelNeverLaunches() {
  assertEquals("APPROVAL_CHANNEL_UNAVAILABLE",assertThrows(ShellExecutionException.class,()->new ShellRunTool(root).execute(request("echo hello"))).getMessage());
 }
 @Test void cancellationIsNotOrdinaryFailure() {
  var source=new CancellationSource();source.cancel();
  assertThrows(OperationCancelledException.class,()->new ShellRunTool(root).execute(request("echo hello"),source.token()));
 }
 @Test void approvalBlocksThenRunsAndSavesBoundedResult()throws Exception {
  var approval=new ApprovalCoordinator(true,e->{});var pool=Executors.newSingleThreadExecutor();
  var context=new ToolExecutionContext(CancellationToken.none(),approval,root.resolve("output"),s->{});
  try {
   var waiting=pool.submit(()->new ShellRunTool(root).execute(request("echo hello"),context));
   long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
   while(approval.pending().isEmpty() && System.nanoTime()<end)Thread.sleep(5);
   assertFalse(waiting.isDone());assertFalse(Files.exists(root.resolve("output")));
   assertTrue(approval.submit(approval.pending().orElseThrow().generation(),ApprovalCoordinator.Decision.ONCE));
   var result=waiting.get(10,TimeUnit.SECONDS);assertTrue(result.isSuccess(),result.getOutput());
   var data=JsonParser.parseString(result.getOutput()).getAsJsonObject();
   assertTrue(data.get("output").getAsString().contains("hello"));assertTrue(data.get("output_saved").getAsBoolean());
   assertTrue(Files.exists(Path.of(data.get("output_file").getAsString())));assertTrue(result.getOutput().length()<=8000);
  } finally{approval.close();pool.shutdownNow();}
 }
 @Test void cleanupFailureTakesPrecedence() {
  var cancelled=new OperationCancelledException();var cleanup=new ResourceCleanupException("still alive",()->{});cancelled.addSuppressed(cleanup);
  assertSame(cleanup,assertThrows(ResourceCleanupException.class,()->ShellExecutor.checkCleanupFailure(cancelled)));
 }
}
