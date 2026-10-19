package com.demo.tools.process;

import java.util.*;

/** Java 17's legacy Windows launcher accepts prequoted native argv elements.
 * Quoting is per element; no shell is involved. Native programs with custom parsers
 * can still interpret their own command line differently from the Windows C runtime.
 */
final class WindowsArguments {
    static List<String> encode(List<String> logical) {
        if ("false".equalsIgnoreCase(System.getProperty("jdk.lang.Process.allowAmbiguousCommands")))
            throw new IllegalArgumentException("WINDOWS_LAUNCHER_CONFIGURATION_UNSUPPORTED");
        List<String> encoded=new ArrayList<>();encoded.add(logical.get(0));
        for(String value:logical.subList(1,logical.size())) {
            StringBuilder result=new StringBuilder("\"");int slashes=0;
            for(int i=0;i<value.length();i++) {
                char c=value.charAt(i);
                if(c=='\\'){slashes++;continue;}
                result.append("\\".repeat(c=='"'?slashes*2+1:slashes));slashes=0;result.append(c);
            }
            result.append("\\".repeat(slashes*2)).append('"');encoded.add(result.toString());
        }
        return List.copyOf(encoded);
    }
}
