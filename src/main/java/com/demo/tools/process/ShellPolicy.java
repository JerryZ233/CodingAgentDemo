package com.demo.tools.process;
public final class ShellPolicy {
 public enum PolicyDecision { DENY, REQUIRE_APPROVAL }
 public PolicyDecision evaluate(ExecutionPlan plan, ShellPolicyConfig config) {
  String os=System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
  var environment=config.profiles().get(plan.profile());
  if (!(os.contains("windows") || os.contains("linux"))
      || !java.util.Set.of("exec","script").contains(plan.mode())
      || !config.version().equals(plan.policyVersion())
      || plan.timeoutMillis()<=0 || plan.timeoutMillis()>900000
      || plan.outputLimit()<=0 || plan.outputLimit()>256*1024
      || !plan.cwd().startsWith(plan.workspace())
      || environment==null || !environment.values().equals(plan.environment())
      || environment.credentials()!=plan.credentials()
      || plan.commandLine().isEmpty() || !plan.commandLine().get(0).equals(plan.executable().toString())
      || (plan.mode().equals("script") && !config.shells().containsKey(plan.shell()))) return PolicyDecision.DENY;
  return PolicyDecision.REQUIRE_APPROVAL;
 }
}
