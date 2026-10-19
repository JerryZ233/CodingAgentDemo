package com.demo.tools.process;

import com.demo.logging.LogData;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OutputPreviewTest {
    @Test void knownSecretSplitAcrossReaderChunksIsNeverEmitted() {
        String secret="preview-secret-unique-0123456789";LogData.registerSecret(secret);
        List<String> events=new ArrayList<>();var preview=new OutputPreview(events::add);
        String first="prefix "+secret.substring(0,12),second=secret.substring(12)+"\nfinished\n";
        preview.accept(first);assertTrue(events.isEmpty());preview.accept(second);
        preview.finish(new ManagedProcess.Result(0,first+second,false,false,false,false,first.length()+second.length(),false));
        String text=String.join("",events);assertFalse(text.contains(secret));assertFalse(text.contains(secret.substring(0,12)));assertTrue(text.contains("[redacted]"));
    }
    @Test void liveBudgetKeepsFinalTail() {
        List<String> events=new ArrayList<>();var preview=new OutputPreview(events::add);
        String text="START\n"+"x\n".repeat(30000)+"FINAL_TAIL";
        preview.accept(text.substring(0,32768));
        preview.finish(new ManagedProcess.Result(0,text,false,false,false,false,text.length(),false));
        String output=String.join("",events);assertTrue(output.contains("START"));assertTrue(output.contains("FINAL_TAIL"));
        assertTrue(output.getBytes(StandardCharsets.UTF_8).length<=32768);
    }
}
