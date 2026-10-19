package com.demo.tools.process;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
public final class ExecutableResolver {
 private final ShellPolicyConfig config; private final Path workspace;
 public ExecutableResolver(ShellPolicyConfig config,Path workspace) {this.config=config;this.workspace=workspace;}
 public Path resolve(String name,Path cwd) throws IOException {
  Path path=config.aliases().get(name);
  if(path==null) {
   Path input=Path.of(name);
   if(input.isAbsolute() || name.contains("/") || name.contains("\\")) path=cwd.resolve(input);
   else for(Path dir:config.searchDirectories()) {
    Path real; try {real=dir.toRealPath();}catch(IOException e){continue;}
    if(real.startsWith(workspace)) continue;
    Path candidate=real.resolve(name);
    if(ShellPolicyConfig.windows() && !name.toLowerCase(Locale.ROOT).endsWith(".exe")) candidate=real.resolve(name+".exe");
    if(Files.isRegularFile(candidate)){path=candidate;break;}
   }
  }
  if(path==null) throw new IllegalArgumentException("EXECUTABLE_UNAVAILABLE");
  Path real=path.toRealPath(); String base=real.getFileName().toString().toLowerCase(Locale.ROOT);
  if(Set.of("cmd.exe","powershell.exe","pwsh.exe","sh","bash","dash","zsh","fish","ksh","csh").contains(base)) throw new IllegalArgumentException("SCRIPT_MODE_REQUIRED");
  for(Path shell:config.shells().values()) if(Files.exists(shell) && Files.isSameFile(shell,real)) throw new IllegalArgumentException("SCRIPT_MODE_REQUIRED");
  if(ShellPolicyConfig.windows() && !base.endsWith(".exe")) throw new IllegalArgumentException("SCRIPT_MODE_REQUIRED");
  if(!Files.isRegularFile(real) || (!ShellPolicyConfig.windows() && !Files.isExecutable(real))) throw new IllegalArgumentException("EXECUTABLE_UNAVAILABLE");
  return real;
 }
 public static String fingerprint(Path path) throws IOException {
  return fingerprint(path,com.demo.execution.CancellationToken.none());
 }
 public static String fingerprint(Path path,com.demo.execution.CancellationToken token) throws IOException {
  BasicFileAttributes before=Files.readAttributes(path,BasicFileAttributes.class);
  if(before.size()>256L*1024*1024)throw new IOException("Executable exceeds fingerprint budget");
  try {
   MessageDigest digest=MessageDigest.getInstance("SHA-256");
   try(InputStream in=Files.newInputStream(path)){
    byte[] b=new byte[32768];int n;long observed=0;
    while((n=in.read(b))!=-1){token.throwIfCancellationRequested();observed+=n;if(observed>256L*1024*1024)throw new IOException("Executable exceeds fingerprint budget");digest.update(b,0,n);}
   }
   BasicFileAttributes after=Files.readAttributes(path,BasicFileAttributes.class);
   if(!before.lastModifiedTime().equals(after.lastModifiedTime()) || before.size()!=after.size() || !Objects.equals(before.fileKey(),after.fileKey()) || !before.creationTime().equals(after.creationTime())) throw new IOException("Entry changed while hashing");
   return "v1:"+before.fileKey()+":"+before.creationTime()+":"+HexFormat.of().formatHex(digest.digest());
  }catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
 }
}
