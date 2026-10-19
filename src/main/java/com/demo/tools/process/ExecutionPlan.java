package com.demo.tools.process;

import java.nio.file.Path;
import java.util.*;
import java.nio.charset.Charset;

/** Exact, host-prepared execution. No model-controlled mutation after approval. */
public record ExecutionPlan(String mode, Path workspace, Path cwd, Path executable,
        String entryFingerprint, String workspaceIdentity, String directoryIdentity, List<String> arguments, String script, String shell,
        List<String> commandLine, Map<String,String> environment, String profile,
        boolean credentials, long timeoutMillis, int outputLimit, Charset encoding,
        String policyVersion) {
    public ExecutionPlan {
        arguments = List.copyOf(arguments); commandLine = List.copyOf(commandLine);
        environment = Collections.unmodifiableMap(new TreeMap<>(environment));
    }
    /** Versioned canonical representation; only its digest is retained in grant/denial sets. */
    public String authorizationKey() {
        com.google.gson.JsonArray canonical=new com.google.gson.JsonArray();
        canonical.add(1);canonical.add(mode);canonical.add(workspace.toString());canonical.add(workspaceIdentity);
        canonical.add(cwd.toString());canonical.add(directoryIdentity);canonical.add(executable.toString());canonical.add(entryFingerprint);
        var gson=new com.google.gson.Gson();canonical.add(gson.toJsonTree(arguments));canonical.add(script);canonical.add(shell);
        canonical.add(gson.toJsonTree(commandLine));canonical.add(gson.toJsonTree(environment));canonical.add(profile);
        canonical.add(credentials);canonical.add(timeoutMillis);canonical.add(outputLimit);canonical.add(encoding.name());canonical.add(policyVersion);
        canonical.add("stdin=closed;merged=true;hard-output=16777216;live=32768;message="+com.demo.tools.support.ToolLimits.MESSAGE_CHARS+";artifact-turn=16777216");
        try {
            return "plan-v1:"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
}
