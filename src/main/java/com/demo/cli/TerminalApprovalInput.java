package com.demo.cli;

import com.demo.session.RunHandle;
import com.demo.tools.process.ApprovalCoordinator;
import com.demo.tools.process.ExecutionPlan;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground-only modal input. It never uses task history or reads from an event thread. */
final class TerminalApprovalInput {
    private final Terminal terminal;
    private final Object outputLock;
    private final StringBuilder draft = new StringBuilder();
    private volatile boolean active;
    private volatile String prompt = "";
    private volatile String displayed = "";
    private AtomicBoolean interruptFlag=new AtomicBoolean();
    TerminalApprovalInput(Terminal terminal, Object lock) { this.terminal=terminal; outputLock=lock; }
    String takeDraft() { String text=draft.toString(); draft.setLength(0); return text; }
    boolean active() { return active; }
    void printAbove(String text) {
        synchronized(outputLock) {
            terminal.writer().print("\r\033[2K"); terminal.writer().println(TerminalIO.literal(text));
            terminal.writer().print(prompt+displayed);terminal.flush();
        }
    }
    TerminalIO.Input read(RunHandle run, boolean singleTask) {
        var attributes=terminal.enterRawMode();
        var interrupted=new AtomicBoolean();
        interruptFlag=interrupted;
        var previous=terminal.handle(Terminal.Signal.INT, signal->interrupted.set(true));
        active=true;
        terminal.writer().print("\033[?2004h");terminal.flush();
        try {
            prompt=singleTask?"":"agent > ";redraw(draft.toString());
            long handledGeneration=-1;
            while(!run.completion().toCompletableFuture().isDone()) {
                var pending=run.pendingApproval().orElse(null);
                if(pending!=null) {
                    if(pending.generation()==handledGeneration) {
                        if(interrupted.getAndSet(false)){run.cancel();return TerminalIO.Input.interrupt();}
                        java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10));
                        continue;
                    }
                    boundary();
                    if(run.pendingApproval().map(a->a.generation()!=pending.generation()).orElse(true))continue;
                    printAbove(approvalText(pending.plan()));
                    prompt="请选择 1 / 2 / 3：";redraw("");
                    StringBuilder answer=new StringBuilder();
                    boolean invalid=false;
                    while(run.pendingApproval().map(a->a.generation()==pending.generation()).orElse(false)) {
                        if(interrupted.getAndSet(false)){run.cancel();break;}
                        int c=terminal.reader().read(25);
                        if(c==NonBlockingReader.READ_EXPIRED)continue;
                        if(c==-1 || c==4){run.cancel();return TerminalIO.Input.eof();}
                        if(c==3){run.cancel();break;}
                        if(c==27) { consumeEscapeOrPaste(); invalid=true; continue; }
                        if(c=='\r'||c=='\n') {
                            String value=answer.toString();answer.setLength(0);
                            if(!invalid && SetCommands.CONTROLS.contains(value)) {
                                boundary();
                                if(value.equals("/exit") || value.equals("/quit")){run.cancel();return TerminalIO.Input.eof();}
                                if(value.equals("/cancel")){run.cancel();return TerminalIO.Input.interrupt();}
                                if(value.equals("/revoke")){run.revokeApprovals();printAbove("已撤销本轮可复用授权；当前命令仍等待决定。");}
                                else printAbove("当前任务：等待命令批准；当前命令尚未启动。");
                                redraw("");continue;
                            }
                            if(!invalid && (value.equals("1")||value.equals("2")||value.equals("3"))) {
                                boundary();
                                var choice=value.equals("1")?ApprovalCoordinator.Decision.ONCE:value.equals("2")?ApprovalCoordinator.Decision.THIS_TURN:ApprovalCoordinator.Decision.DENY;
                                run.submitApproval(pending.generation(),choice);handledGeneration=pending.generation();break;
                            }
                            boundary();invalid=false;
                            printAbove("请输入单个数字 1、2 或 3 并回车；粘贴内容不用于批准。");redraw("");
                        } else {
                            if(answer.length()<256)answer.append((char)c);else invalid=true;
                            redraw(answer.toString());
                        }
                    }
                    terminal.writer().println();prompt=singleTask?"":"agent > ";redraw(draft.toString());
                    continue;
                }
                if(interrupted.getAndSet(false)){run.cancel();return TerminalIO.Input.interrupt();}
                int c=terminal.reader().read(25);
                if(c==NonBlockingReader.READ_EXPIRED)continue;
                if(c==-1||c==4){run.cancel();return TerminalIO.Input.eof();}
                if(c==3){run.cancel();return TerminalIO.Input.interrupt();}
                if(run.pendingApproval().isPresent())continue;
                if(c==27){consumeEscapeOrPaste();continue;}
                if(c=='\r'||c=='\n') {
                    String text=takeDraft();terminal.writer().println();
                    if(!singleTask || !text.isBlank())return TerminalIO.Input.line(text);
                } else if(c==127||c==8) { if(!draft.isEmpty())draft.deleteCharAt(draft.length()-1);redraw(draft.toString()); }
                else if(c>=32 && draft.length()<8192){draft.append((char)c);redraw(draft.toString());}
            }
            return new TerminalIO.Input(TerminalIO.Kind.WAKE,null);
        } catch(InputInterrupted e){run.cancel();return TerminalIO.Input.interrupt();}
        catch(EOFException e){run.cancel();return TerminalIO.Input.eof();}
        catch(IOException e){run.cancel();throw new UncheckedIOException("无法建立安全的审批输入边界",e);}
        finally {
            active=false;terminal.writer().print("\r\033[2K\033[?2004l");terminal.flush();
            terminal.setAttributes(attributes);terminal.handle(Terminal.Signal.INT,previous);
        }
    }
    private void boundary() throws IOException {
        int discarded=0,c;
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while((c=terminal.reader().read(40))!=NonBlockingReader.READ_EXPIRED) {
            if(interruptFlag.get())throw new InputInterrupted();
            if(c==-1)throw new EOFException();
            if(c==3)throw new InputInterrupted();
            if(c==4)throw new EOFException();
            if(c==27)consumeEscapeOrPaste();
            if(++discarded>65536 || System.nanoTime()>deadline)throw new IOException("Input boundary unavailable");
        }
    }
    private static final class InputInterrupted extends IOException { }
    private static final class SetCommands {
        static final java.util.Set<String> CONTROLS=java.util.Set.of("/cancel","/exit","/quit","/status","/revoke");
    }
    private void consumeEscapeOrPaste() throws IOException {
        StringBuilder sequence=new StringBuilder();
        for(int i=0;i<5;i++) {
            int c=terminal.reader().read(40);if(c<0)return;sequence.append((char)c);
            if(c=='~'||Character.isLetter(c))break;
        }
        if(sequence.toString().equals("[200~")) {
            StringBuilder end=new StringBuilder();int count=0;
            while(!end.toString().endsWith("\033[201~")) {
                int c=terminal.reader().read(100);
                if(c==3 || interruptFlag.get())throw new InputInterrupted();
                if(c==NonBlockingReader.READ_EXPIRED){if(++count>50)throw new IOException("Unterminated paste");continue;}
                if(c==-1)throw new EOFException();
                end.append((char)c);if(end.length()>6)end.deleteCharAt(0);
            }
        }
    }
    private void redraw(String text) {
        synchronized(outputLock){displayed=TerminalIO.literal(text);terminal.writer().print("\r\033[2K"+prompt+displayed);terminal.flush();}
    }
    static String approvalText(ExecutionPlan p) {
        String command=p.mode().equals("exec")?"程序："+p.executable()+"\n参数："+new com.google.gson.Gson().toJson(p.arguments())
                :"Shell："+p.shell()+"\n完整脚本：\n"+p.script();
        return TerminalIO.literal(com.demo.logging.LogData.redact("需要执行命令\n\n"+command+"\n目录："+(p.cwd().equals(p.workspace())?"当前项目目录":p.workspace().relativize(p.cwd()))
                +"\n环境："+(p.profile().equals("default")?"默认开发环境":p.profile())+(p.credentials()?"（包含宿主配置的凭据）":"")+"\n执行上限："+p.timeoutMillis()/1000.0+" 秒\n\n"
                +"本机以当前用户权限执行，可能访问工作区外文件和网络。\n1. 批准本次\n2. 本轮允许相同命令\n3. 拒绝\n"
                +"本轮允许重复执行相同计划；项目文件可能在两次执行之间变化。\n"));
    }
}
