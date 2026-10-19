package com.demo.tools.process;
import com.demo.tools.fs.WorkspaceFileAccess;
import com.demo.tools.support.ToolArguments;
import java.nio.file.*;
import java.nio.charset.*;
import java.util.*;
import java.io.IOException;
public final class CommandPlanner {
 private final WorkspaceFileAccess workspace; private final ShellPolicyConfig config; private final ExecutableResolver resolver;
 public CommandPlanner(Path root,ShellPolicyConfig config){workspace=new WorkspaceFileAccess(root);this.config=config;resolver=new ExecutableResolver(config,workspace.root());}
 public ExecutionPlan prepare(String json) throws IOException {
  return prepare(json,com.demo.execution.CancellationToken.none());
 }
 public ExecutionPlan prepare(String json,com.demo.execution.CancellationToken token) throws IOException {
  token.throwIfCancellationRequested();
  ShellPlatform platform=ShellPlatform.current();
  var a=ToolArguments.parse(json,"mode","executable","args","script","shell","cwd","environment_profile","timeout_ms","command");
  if(a.has("command"))throw new IllegalArgumentException("LEGACY_COMMAND_UNSUPPORTED: use mode=exec with executable/args or mode=script with script");
  String mode=a.requiredString("mode"); if(!Set.of("exec","script").contains(mode))throw new IllegalArgumentException("INVALID_MODE");
  workspace.verifyBinding();
  String cwdText=Optional.ofNullable(a.optionalString("cwd")).orElse(".");validText(cwdText);
  if(cwdText.length()>1024)throw new IllegalArgumentException("CWD_TOO_LONG");
  Path cwd=workspace.root().resolve(cwdText).normalize().toRealPath();
  if(!cwd.startsWith(workspace.root()) || !Files.isDirectory(cwd))throw new IllegalArgumentException("INVALID_CWD");
  String profile=Optional.ofNullable(a.optionalString("environment_profile")).orElse("default"); validText(profile);
  var env=config.profiles().get(profile);if(env==null)throw new IllegalArgumentException("ENVIRONMENT_UNAVAILABLE");
  int timeout=a.integer("timeout_ms",120000,1,900000); List<String> args=List.of(); String script=null,shell=null; Path entry;
  List<String> launch=new ArrayList<>();
  if(mode.equals("exec")) {
   if(a.has("script") || a.has("shell"))throw new IllegalArgumentException("MIXED_MODE_FIELDS");
   String executable=a.requiredString("executable");validText(executable);args=a.strings("args");
   if(args.size()>128)throw new IllegalArgumentException("ARGUMENT_LIMIT_EXCEEDED");
   int length=executable.length();for(String arg:args){validText(arg);length+=arg.length();}
   if(length>8192)throw new IllegalArgumentException("ARGUMENT_LIMIT_EXCEEDED");
   entry=resolver.resolve(executable,cwd);launch.add(entry.toString());launch.addAll(args);
  } else {
   if(a.has("executable") || a.has("args"))throw new IllegalArgumentException("MIXED_MODE_FIELDS");
   script=a.requiredString("script");validText(script);if(script.length()>4096)throw new IllegalArgumentException("SCRIPT_LIMIT_EXCEEDED");
   shell=Optional.ofNullable(a.optionalString("shell")).orElse(platform.defaultShell());
   if(!platform.supports(shell))throw new IllegalArgumentException("SHELL_UNAVAILABLE");
   Path configured=config.shells().get(shell);if(configured==null)throw new IllegalArgumentException("SHELL_UNAVAILABLE");
   entry=configured.toRealPath();if(!Files.isRegularFile(entry))throw new IllegalArgumentException("SHELL_UNAVAILABLE");launch.add(entry.toString());
   launch=new ArrayList<>(platform.script(entry,shell,script));
  }
  if(mode.equals("exec"))launch=new ArrayList<>(platform.direct(launch));
  platform.checkLength(launch);
  Charset encoding=platform.encoding(shell);
  return new ExecutionPlan(mode,workspace.root(),cwd,entry,ExecutableResolver.fingerprint(entry,token),directoryIdentity(workspace.root()),directoryIdentity(cwd),args,script,shell,launch,env.values(),profile,env.credentials(),timeout,256*1024,encoding,config.version());
 }
 public boolean valid(ExecutionPlan p)throws IOException {
  return valid(p,com.demo.execution.CancellationToken.none());
 }
 public boolean valid(ExecutionPlan p,com.demo.execution.CancellationToken token)throws IOException {
  token.throwIfCancellationRequested();
  workspace.verifyBinding();return stillMatches(p,token);
 }
 static boolean stillMatches(ExecutionPlan p,com.demo.execution.CancellationToken token)throws IOException {
  token.throwIfCancellationRequested();
  return p.workspace().toRealPath().equals(p.workspace()) && directoryIdentity(p.workspace()).equals(p.workspaceIdentity())
      && directoryIdentity(p.cwd()).equals(p.directoryIdentity()) && p.cwd().toRealPath().equals(p.cwd())
      && p.cwd().startsWith(p.workspace()) && p.executable().toRealPath().equals(p.executable())
      && ExecutableResolver.fingerprint(p.executable(),token).equals(p.entryFingerprint());
 }
 private static String directoryIdentity(Path path) throws IOException {
  var a=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class);
  return "v1:"+a.fileKey()+":"+a.creationTime();
 }
 public static void validText(String s) {
  for(int i=0;i<s.length();i++) {
   char c=s.charAt(i);if(c==0)throw new IllegalArgumentException("NUL_NOT_ALLOWED");
   if(Character.isHighSurrogate(c)){if(++i>=s.length() || !Character.isLowSurrogate(s.charAt(i)))throw new IllegalArgumentException("INVALID_UNICODE");}
   else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("INVALID_UNICODE");
  }
 }
}
