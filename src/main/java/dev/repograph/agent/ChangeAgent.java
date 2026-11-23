package dev.repograph.agent;

import dev.repograph.scan.Repo;
import dev.repograph.tools.GraphTools;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Carries out a contract change across repositories: plans it with the code graph, edits every
 * affected repo in dependency order, verifies each with its own build, and opens linked pull requests.
 *
 * <p>The model plans and edits. Everything that decides whether the result is trustworthy is done
 * here, deterministically: which repos exist, the order, running the builds, committing and opening PRs.
 */
public final class ChangeAgent {

  private static final int MAX_ATTEMPTS = 3;
  private static final List<String> READ_TOOLS = List.of("Read", "Glob", "Grep");
  private static final List<String> EDIT_TOOLS = List.of("Read", "Glob", "Grep", "Edit", "Write");

  /** Outcome per repo. */
  record RepoResult(Repo repo, ChangePlan.RepoChange change, String status, int attempts, String diffStat,
      String modelSummary, String prUrl) {
  }

  private final Path workspace;
  private final List<Repo> repos;
  private final GraphTools graph;
  private final ClaudeCode claude;
  private final String mvn;
  private final boolean push;
  private final Consumer<String> log;
  private double costUsd;

  public ChangeAgent(Path workspace, List<Repo> repos, GraphTools graph, ClaudeCode claude, String mvn,
      boolean push, Consumer<String> log) {
    this.workspace = workspace;
    this.repos = repos;
    this.graph = graph;
    this.claude = claude;
    this.mvn = mvn;
    this.push = push;
    this.log = log;
  }

  /** Runs the change; returns the path of the run report. */
  public Path run(String request) {
    long start = System.currentTimeMillis();
    Map<String, String> baseBranches = preflight();

    log.accept("Planning: " + request);
    ClaudeCode.Reply planReply = claude.run(workspace, planPrompt(request),
        new ClaudeCode.Options(READ_TOOLS, false, ChangePlan.SCHEMA, null));
    costUsd += planReply.costUsd();
    ChangePlan plan = ClaudeCode.convert(planReply.structured(), ChangePlan.class);
    String branch = "change/" + plan.branch();
    List<ChangePlan.RepoChange> ordered = order(plan);
    log.accept("Plan (%d turns, %ds): %s".formatted(planReply.turns(), planReply.millis() / 1000, plan.title()));
    ordered.forEach(c -> log.accept("  - " + c.repo() + ": " + oneLine(c.why())));

    List<RepoResult> results = new ArrayList<>();
    for (ChangePlan.RepoChange change : ordered) {
      Repo repo = repo(change.repo());
      RepoResult result = applyChange(request, plan, change, repo, branch, results);
      results.add(result);
      if ("failed".equals(result.status())) {
        log.accept("Stopping: " + repo.name() + " is not green after " + MAX_ATTEMPTS + " attempts. Branches are left "
            + "in place for inspection.");
        return report(request, plan, branch, results, start);
      }
    }

    List<RepoResult> changed = results.stream().filter(r -> "green".equals(r.status())).toList();
    if (push && !changed.isEmpty()) {
      results = openPullRequests(request, plan, branch, results, baseBranches);
    }
    return report(request, plan, branch, results, start);
  }

  /** Every repo must be a clean git checkout; returns each repo's current (base) branch. */
  private Map<String, String> preflight() {
    Map<String, String> branches = new LinkedHashMap<>();
    for (Repo repo : repos) {
      String status = Proc.check(repo.dir(), "git", "status", "--porcelain");
      if (!status.isEmpty()) {
        throw new IllegalStateException(repo.name() + " has uncommitted changes; commit or stash them first.");
      }
      branches.put(repo.name(), Proc.check(repo.dir(), "git", "rev-parse", "--abbrev-ref", "HEAD"));
    }
    return branches;
  }

  private String planPrompt(String request) {
    return """
        You are planning a change that spans several repositories in this workspace (the current directory;
        each subdirectory with a pom.xml is a separate git repository). Requested change:

        %s

        Use the repograph tools to find every affected repository: start with overview, then
        impact_of_change / find_usages / trace_flow, and read the code with get_source or Read before deciding.
        Include a repository only if its code must change to compile, keep its tests green, or keep its
        HTTP/Kafka contract consistent with the rest of the system. List the repositories in a safe
        deployment order: shared libraries first, then services that receive or consume new data before
        the services that start sending it. Maven dependencies override this order where they apply.

        For each repository give concrete instructions: files, symbols, and the tests to update. Do not
        plan version bumps or build changes. List risks the graph cannot rule out (for example JSON
        consumers outside the workspace or mixed versions during deployment).
        """.formatted(request);
  }

  /** Keeps known repos only, in dependency order (providers first). */
  private List<ChangePlan.RepoChange> order(ChangePlan plan) {
    Map<String, ChangePlan.RepoChange> byName = new LinkedHashMap<>();
    for (ChangePlan.RepoChange change : plan.repos()) {
      if (repos.stream().noneMatch(r -> r.name().equals(change.repo()))) {
        log.accept("  ! plan names unknown repo '" + change.repo() + "', ignored");
      } else {
        byName.putIfAbsent(change.repo(), change);
      }
    }
    // Maven dependencies decide; between independent repos the plan's deployment order is kept.
    List<ChangePlan.RepoChange> ordered = new ArrayList<>();
    Set<String> visited = new LinkedHashSet<>();
    for (String name : byName.keySet()) {
      visit(repo(name), visited, byName, ordered);
    }
    return ordered;
  }

  private void visit(Repo repo, Set<String> visited, Map<String, ChangePlan.RepoChange> planned,
      List<ChangePlan.RepoChange> ordered) {
    if (!visited.add(repo.name())) {
      return;
    }
    for (String dependency : repo.dependencies()) {
      repos.stream().filter(r -> r.artifactId().equals(dependency)).findFirst()
          .ifPresent(r -> visit(r, visited, planned, ordered));
    }
    if (planned.containsKey(repo.name())) {
      ordered.add(planned.get(repo.name()));
    }
  }

  private RepoResult applyChange(String request, ChangePlan plan, ChangePlan.RepoChange change, Repo repo,
      String branch, List<RepoResult> done) {
    log.accept("[" + repo.name() + "] editing on " + branch);
    Proc.check(repo.dir(), "git", "checkout", "-b", branch);
    ClaudeCode.Reply reply = claude.run(repo.dir(), editPrompt(request, plan, change, done),
        new ClaudeCode.Options(EDIT_TOOLS, true, null, null));
    costUsd += reply.costUsd();
    String modelSummary = reply.text();

    for (int attempt = 1; ; attempt++) {
      if (Proc.check(repo.dir(), "git", "status", "--porcelain").isEmpty()) {
        log.accept("[" + repo.name() + "] no changes needed");
        Proc.check(repo.dir(), "git", "checkout", "-");
        Proc.check(repo.dir(), "git", "branch", "-D", branch);
        return new RepoResult(repo, change, "unchanged", attempt, "", modelSummary, null);
      }
      log.accept("[" + repo.name() + "] build attempt " + attempt + ": mvn install");
      // install, not verify: downstream repos in this change build against the new artifact.
      Proc.Result build = Proc.run(repo.dir(), List.of(mvn, "-B", "-q", "install"), null, Map.of(),
          Duration.ofMinutes(15));
      if (build.ok()) {
        Proc.check(repo.dir(), "git", "add", "-A");
        Proc.check(repo.dir(), "git", "commit", "-q", "-m", plan.title() + "\n\n" + change.why());
        String stat = Proc.check(repo.dir(), "git", "diff", "--stat", "HEAD~1");
        log.accept("[" + repo.name() + "] green, committed");
        return new RepoResult(repo, change, "green", attempt, stat, modelSummary, null);
      }
      if (attempt == MAX_ATTEMPTS) {
        return new RepoResult(repo, change, "failed", attempt, "", build.tail(60), null);
      }
      log.accept("[" + repo.name() + "] build failed, sending the output back to the model");
      ClaudeCode.Reply fix = claude.run(repo.dir(), """
          The build failed after your change. Fix the cause; do not delete or disable tests. Afterwards,
          reply with the complete pull request summary again (all edits, not only this fix).
          Last lines of `mvn install`:

          %s
          """.formatted(build.tail(80)), new ClaudeCode.Options(EDIT_TOOLS, true, null, reply.sessionId()));
      costUsd += fix.costUsd();
      modelSummary = fix.text();
    }
  }

  private String editPrompt(String request, ChangePlan plan, ChangePlan.RepoChange change, List<RepoResult> done) {
    StringBuilder upstream = new StringBuilder();
    for (RepoResult result : done) {
      if ("green".equals(result.status())) {
        upstream.append("\n### ").append(result.repo().name()).append(" (done, installed in the local Maven repo)\n")
            .append("```diff\n").append(truncate(Proc.check(result.repo().dir(), "git", "diff", "HEAD~1"), 150))
            .append("\n```\n");
      }
    }
    return """
        You are making one part of a coordinated change across several repositories.

        Requested change: %s
        Plan: %s

        Your repository is the current directory (%s). Edit files in it only.
        What to change here: %s
        Why: %s
        %s
        Rules:
        - Make the minimal change that fulfils the request; keep the existing style.
        - Update the tests that reference what you changed. Do not delete or disable tests.
        - Do not change versions, dependencies or build files.
        - The repograph tools describe the code BEFORE this change; use them to find references.
        - You cannot run the build. When you finish, the harness runs `mvn install` and sends you failures.
        Finish with a summary for the pull request description: a few bullet points of what changed, per
        file. Do not mention building or testing; the harness reports verification itself.
        """.formatted(request, plan.summary(), change.repo(), change.instructions(), change.why(),
        upstream.isEmpty() ? "" : "\nAlready changed in this change set:\n" + upstream);
  }

  private List<RepoResult> openPullRequests(String request, ChangePlan plan, String branch, List<RepoResult> results,
      Map<String, String> baseBranches) {
    List<RepoResult> withPrs = new ArrayList<>();
    for (RepoResult result : results) {
      if (!"green".equals(result.status())) {
        withPrs.add(result);
        continue;
      }
      Path dir = result.repo().dir();
      log.accept("[" + result.repo().name() + "] pushing and opening PR");
      Proc.check(dir, "git", "push", "-q", "-u", "origin", branch);
      String url = Proc.check(dir, "gh", "pr", "create", "--base", baseBranches.get(result.repo().name()),
          "--head", branch, "--title", plan.title(), "--body", "Linking the change set...");
      withPrs.add(new RepoResult(result.repo(), result.change(), result.status(), result.attempts(),
          result.diffStat(), result.modelSummary(), url.lines().reduce((a, b) -> b).orElse(url)));
    }
    // Second pass: every PR lists all the others, which only exist now.
    for (RepoResult result : withPrs) {
      if (result.prUrl() != null) {
        Proc.check(result.repo().dir(), "gh", "pr", "edit", result.prUrl(), "--body",
            prBody(request, plan, branch, result, withPrs));
      }
    }
    return withPrs;
  }

  private String prBody(String request, ChangePlan plan, String branch, RepoResult self, List<RepoResult> all) {
    StringBuilder body = new StringBuilder();
    body.append(plan.summary()).append("\n\n## This repository\n").append(self.change().why()).append("\n\n")
        .append(self.modelSummary().strip()).append("\n\n## Change set (merge in this order)\n");
    int n = 1;
    for (RepoResult r : all) {
      if (r.prUrl() != null) {
        body.append(n++).append(". **").append(r.repo().name()).append("** ").append(r.prUrl())
            .append(r == self ? " (this PR)" : "").append('\n');
      }
    }
    body.append("\nServices build against `bank-contracts` from branch `").append(branch)
        .append("` in CI until it is merged.\n");
    if (!plan.risks().isEmpty()) {
      body.append("\n## Risks for review\n");
      plan.risks().forEach(risk -> body.append("- ").append(risk).append('\n'));
    }
    body.append("\n## Verification\n`mvn install` (all tests) passed locally");
    body.append(self.attempts() > 1 ? " after " + self.attempts() + " attempts" : "").append(".\n");
    String impact = impactAppendix(plan);
    if (!impact.isEmpty()) {
      body.append("\n## Impact analysis (repograph)\n").append(impact);
    }
    body.append("\n---\nOpened by the repograph change agent for the request: _").append(request).append("_\n");
    return body.toString();
  }

  private String impactAppendix(ChangePlan plan) {
    StringBuilder out = new StringBuilder();
    for (String field : plan.fields()) {
      try {
        out.append("<details><summary><code>").append(field).append("</code></summary>\n\n```text\n")
            .append(truncate(graph.impactOfChange(field), 80)).append("\n```\n</details>\n");
      } catch (RuntimeException e) {
        // A field the graph cannot resolve is left out of the appendix; the plan text still covers it.
        log.accept("  ! no impact data for " + field + ": " + e.getMessage());
      }
    }
    return out.toString();
  }

  private Path report(String request, ChangePlan plan, String branch, List<RepoResult> results, long start) {
    StringBuilder md = new StringBuilder("# ").append(plan.title()).append("\n\n")
        .append("- Request: ").append(request).append('\n')
        .append("- Branch: `").append(branch).append("`\n")
        .append("- Duration: ").append((System.currentTimeMillis() - start) / 1000).append(" s\n")
        .append("- Model cost (list price; covered by a subscription): $").append("%.2f".formatted(costUsd))
        .append("\n\n| Repo | Result | Build attempts | PR |\n|---|---|---|---|\n");
    for (RepoResult r : results) {
      md.append("| ").append(r.repo().name()).append(" | ").append(r.status()).append(" | ").append(r.attempts())
          .append(" | ").append(r.prUrl() == null ? "-" : r.prUrl()).append(" |\n");
    }
    md.append("\n## Plan\n").append(plan.summary()).append('\n');
    for (RepoResult r : results) {
      md.append("\n### ").append(r.repo().name()).append("\n").append(r.change().why()).append("\n\n")
          .append(r.modelSummary().strip()).append('\n');
      if (!r.diffStat().isEmpty()) {
        md.append("\n```text\n").append(r.diffStat()).append("\n```\n");
      }
    }
    try {
      Path dir = workspace.resolve(".repograph-runs");
      Files.createDirectories(dir);
      Path file = dir.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + "-"
          + plan.branch() + ".md");
      Files.writeString(file, md);
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Repo repo(String name) {
    return repos.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
  }

  private static String oneLine(String text) {
    String line = text.lines().findFirst().orElse("");
    return line.length() > 140 ? line.substring(0, 140) + "..." : line;
  }

  private static String truncate(String text, int maxLines) {
    List<String> lines = text.lines().toList();
    return lines.size() <= maxLines ? text
        : String.join("\n", lines.subList(0, maxLines)) + "\n... (" + (lines.size() - maxLines) + " more lines)";
  }
}
