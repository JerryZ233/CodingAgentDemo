package com.demo.tools.process;

import java.nio.file.Path;
import java.nio.charset.*;
import java.util.*;

/** Host-generated platform launch settings; these are part of the approved plan. */
public enum ShellPlatform {
    WINDOWS("powershell",Set.of("cmd","powershell","pwsh")),
    LINUX("sh",Set.of("sh","bash"));
    private final String defaultShell;
    private final Set<String> shells;
    ShellPlatform(String defaultShell,Set<String> shells){this.defaultShell=defaultShell;this.shells=shells;}
    public static ShellPlatform current() {
        String os=System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if(os.contains("windows"))return WINDOWS;
        if(os.contains("linux"))return LINUX;
        throw new IllegalArgumentException("UNSUPPORTED_PLATFORM");
    }
    public String defaultShell(){return defaultShell;}
    public boolean supports(String shell){return shells.contains(shell);}
    public List<String> direct(List<String> arguments) {
        return this==WINDOWS?WindowsArguments.encode(arguments):List.copyOf(arguments);
    }
    public List<String> script(Path entry,String shell,String script) {
        if(!supports(shell))throw new IllegalArgumentException("SHELL_UNAVAILABLE");
        List<String> launch=new ArrayList<>();launch.add(entry.toString());
        switch(shell) {
            case "cmd" -> launch.addAll(List.of("/d","/s","/v:off","/c",script));
            case "powershell","pwsh" -> launch.addAll(List.of("-NoLogo","-NoProfile","-NonInteractive","-OutputFormat","Text",
                    "-EncodedCommand",Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE))));
            case "bash" -> launch.addAll(List.of("--noprofile","--norc","-c",script));
            case "sh" -> launch.addAll(List.of("-c",script));
            default -> throw new IllegalArgumentException("SHELL_UNAVAILABLE");
        }
        return List.copyOf(launch);
    }
    public Charset encoding(String shell) {
        if(this==LINUX || "pwsh".equals(shell))return StandardCharsets.UTF_8;
        try{return Charset.forName(System.getProperty("native.encoding",Charset.defaultCharset().name()));}
        catch(IllegalArgumentException e){return Charset.defaultCharset();}
    }
    public void checkLength(List<String> launch) {
        if(this==WINDOWS && launch.stream().mapToInt(s->s.length()+1).sum()>30000)
            throw new IllegalArgumentException("PLATFORM_ARGUMENT_LIMIT_EXCEEDED");
    }
}
