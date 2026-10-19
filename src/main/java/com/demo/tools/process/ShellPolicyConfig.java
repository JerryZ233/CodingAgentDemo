package com.demo.tools.process;
import java.nio.file.*;
import java.util.*;
/** Complete frozen host environment snapshots. Never accept variables from model arguments. */
public record ShellPolicyConfig(Map<String,Path> aliases, List<Path> searchDirectories,
 Map<String,EnvironmentProfile> profiles, Map<String,Path> shells, String version) {
 public record EnvironmentProfile(Map<String,String> values, boolean credentials) {
  public EnvironmentProfile {
   Map<String,String> normalized=new HashMap<>();
   for(var e:values.entrySet()) {
    String key=windows()?e.getKey().toUpperCase(Locale.ROOT):e.getKey();
    if(normalized.put(key,e.getValue())!=null)throw new IllegalArgumentException("Duplicate environment name");
   }
   values=Map.copyOf(normalized);
   int size=0;
   for(var e:values.entrySet()) {
    CommandPlanner.validText(e.getKey()); CommandPlanner.validText(e.getValue());
    if(e.getKey().isBlank() || e.getKey().contains("=")) throw new IllegalArgumentException("Invalid environment name");
    if(Set.of("BASH_ENV","ENV","SHELLOPTS","BASHOPTS","CDPATH","PSMODULEPATH").contains(e.getKey().toUpperCase(Locale.ROOT)))throw new IllegalArgumentException("Shell startup injection is unsupported");
    size+=e.getKey().length()+e.getValue().length()+2;
    String key=e.getKey().toUpperCase(Locale.ROOT);
    boolean sensitive=key.matches(".*(API_KEY|TOKEN|PASSWORD|SECRET|CREDENTIAL|AUTHORIZATION).*");
    if(sensitive && !credentials)throw new IllegalArgumentException("Credential environment requires credentials=true");
    if(credentials && sensitive)com.demo.logging.LogData.registerSecret(e.getValue());
   }
   if(size>(windows()?32700:131072))throw new IllegalArgumentException("Environment limit exceeded");
  }
 }
 public ShellPolicyConfig {
  aliases=Map.copyOf(aliases); searchDirectories=List.copyOf(searchDirectories);
  profiles=Map.copyOf(profiles); shells=Map.copyOf(shells); Objects.requireNonNull(version);
  for(Path path:searchDirectories) if(!path.isAbsolute()) throw new IllegalArgumentException("Trusted directory must be absolute");
  for(Path path:aliases.values()) if(!path.isAbsolute()) throw new IllegalArgumentException("Alias must be absolute");
  for(Path path:shells.values()) if(!path.isAbsolute()) throw new IllegalArgumentException("Shell must be absolute");
 }
 public static boolean windows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"); }
 public static ShellPolicyConfig defaults() {
  Map<String,String> env=new HashMap<>(); Map<String,Path> shells=new HashMap<>(); List<Path> dirs=new ArrayList<>();
  if(windows()) {
   Path system=Path.of(System.getenv().getOrDefault("SystemRoot","C:\\Windows")).toAbsolutePath();
   env.put("SystemRoot",system.toString()); env.put("WINDIR",system.toString());
   env.put("ComSpec",system.resolve("System32/cmd.exe").toString());
   env.put("OS","Windows_NT");env.put("PATHEXT",".COM;.EXE;.BAT;.CMD");
   env.put("TEMP",System.getProperty("java.io.tmpdir")); env.put("TMP",System.getProperty("java.io.tmpdir"));
   dirs.add(system.resolve("System32")); dirs.add(system);
   shells.put("cmd",system.resolve("System32/cmd.exe"));
   shells.put("powershell",system.resolve("System32/WindowsPowerShell/v1.0/powershell.exe"));
  } else {
   dirs.add(Path.of("/usr/bin")); dirs.add(Path.of("/bin"));
   env.put("LANG","C.UTF-8"); env.put("LC_ALL","C.UTF-8");
   shells.put("sh",Path.of("/bin/sh")); shells.put("bash",Path.of("/bin/bash"));
  }
  env.put("PATH",String.join(java.io.File.pathSeparator,dirs.stream().map(Path::toString).toList()));
  return new ShellPolicyConfig(Map.of(),dirs,Map.of("default",new EnvironmentProfile(env,false)),shells,"shell-policy-v1");
 }
}
