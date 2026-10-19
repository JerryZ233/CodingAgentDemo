package com.demo.tools.builtin;
import com.demo.tools.*;
import com.demo.tools.process.*;
import com.demo.tools.support.*;
import com.demo.model.ToolResult;
import com.demo.execution.*;
import com.google.gson.*;
import java.nio.file.Path;
import java.util.Set;

public final class ShellRunTool implements Tool {
 private final ShellPolicyConfig config; private final CommandPlanner planner;
 public ShellRunTool(Path workspace){this(workspace,ShellPolicyConfig.defaults());}
 public ShellRunTool(Path workspace,ShellPolicyConfig config){this.config=config;planner=new CommandPlanner(workspace,config);}
 public String getName(){return "run_shell";}
 public String getDescription(){return "Run a foreground program (exec: executable plus exact args) or explicit script with user approval. Runs as the current user, with access beyond the workspace and to the network. No interactive input, PTY or persistent servers. Windows batch files require script mode. Use --no-daemon for builds. PowerShell native command scripts should explicitly exit $LASTEXITCODE. Output previews may be truncated; do not treat them as complete output.";}
 public Set<ToolSpec.Capability> getCapabilities(){return Set.of(ToolSpec.Capability.PROCESS_EXECUTE,ToolSpec.Capability.NETWORK);}
 public JsonObject getParametersSchema(){return JsonParser.parseString("""
 {"type":"object","properties":{
 "mode":{"type":"string","enum":["exec","script"]},
 "executable":{"type":"string"},"args":{"type":"array","maxItems":128,"items":{"type":"string"}},
 "script":{"type":"string","maxLength":4096},"shell":{"type":"string"},"cwd":{"type":"string"},
 "environment_profile":{"type":"string"},"timeout_ms":{"type":"integer","minimum":1,"maximum":900000}},
 "required":["mode"],"additionalProperties":false}
 """).getAsJsonObject();}
 public ToolResult execute(String args){return execute(args,CancellationToken.none());}
 public ToolResult execute(String args,CancellationToken token){return execute(args,ToolExecutionContext.unavailable(token));}
 public ToolResult execute(String args,ToolExecutionContext context){
  for(int attempt=0;attempt<4;attempt++) {
    ExecutionPlan plan=null;
    try {
    context.token().throwIfCancellationRequested();
    plan=planner.prepare(args,context.token());
    if(new ShellPolicy().evaluate(plan,config)==ShellPolicy.PolicyDecision.DENY)return ShellExecutor.failure("POLICY_DENIED",false);
    if(!context.approvals().authorize(plan,context.token()))return ShellExecutor.failure("USER_DENIED",false);
    context.token().throwIfCancellationRequested();
    if(!planner.valid(plan,context.token())){context.approvals().invalidate(plan);continue;}
    return new ShellExecutor().execute(plan,context);
   }catch(ShellCancelledException e){throw e;}
    catch(OperationCancelledException e){throw new ShellCancelledException(false);}
    catch(ShellExecutionException e){if("PLAN_INVALIDATED".equals(e.getMessage()) && plan!=null){context.approvals().invalidate(plan);continue;}throw e;}
    catch(ResourceCleanupException e){throw e;}
    catch(com.demo.tools.fs.FileAccessException e){return ShellExecutor.failure("WORKSPACE_BINDING_INVALID",false);}
    catch(IllegalArgumentException e){return ToolOutputs.error(getName(),"INVALID_ARGUMENTS",e.getMessage());}
    catch(java.io.IOException e){return ShellExecutor.failure("PLAN_UNAVAILABLE",false);}
  }
  throw new ShellExecutionException("PLAN_UNSTABLE");
 }
}
