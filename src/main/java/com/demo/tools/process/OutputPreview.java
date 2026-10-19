package com.demo.tools.process;

import com.demo.logging.LogData;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Bounded live head and final tail. Redact complete lines before emitting chunks. */
final class OutputPreview {
    private final Consumer<String> sink;
    private final StringBuilder buffered=new StringBuilder(), captured=new StringBuilder();
    private int emitted;
    OutputPreview(Consumer<String> sink){this.sink=sink;}
    synchronized void accept(String text) {
        captured.append(text);buffered.append(text);
        if(emitted>=16384)return;
        int cut=LogData.safeOutputBoundary(buffered.toString());
        if(cut>0){emit(LogData.redact(buffered.substring(0,cut)),16384);buffered.delete(0,cut);}
    }
    synchronized void finish(ManagedProcess.Result result) {
        if(!result.truncated() && result.output().equals(captured.toString()))emit(LogData.redact(buffered.toString()),32768);
        else {
            String text=LogData.redact(result.output());
            String marker="\n[live output preview; tail follows]\n";
            int available=32768-emitted-marker.getBytes(StandardCharsets.UTF_8).length;
            if(available>0)emit(marker+suffix(text,available),32768);
        }
        captured.setLength(0);buffered.setLength(0);
    }
    private void emit(String text,int ceiling) {
        int budget=ceiling-emitted;if(budget<=0)return;
        String value=prefix(text,budget);if(value.isEmpty())return;
        emitted+=value.getBytes(StandardCharsets.UTF_8).length;sink.accept(value);
    }
    private static String prefix(String text,int bytes) {
        int index=0,size=0;
        while(index<text.length()) {
            int cp=text.codePointAt(index),next=cp<128?1:cp<2048?2:cp<65536?3:4;
            if(size+next>bytes)break;size+=next;index+=Character.charCount(cp);
        }
        return text.substring(0,index);
    }
    private static String suffix(String text,int bytes) {
        int index=text.length(),size=0;
        while(index>0) {
            int cp=text.codePointBefore(index),next=cp<128?1:cp<2048?2:cp<65536?3:4;
            if(size+next>bytes)break;size+=next;index-=Character.charCount(cp);
        }
        return text.substring(index);
    }
}
