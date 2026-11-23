package dev.repograph.agent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The model, reached through headless Claude Code ({@code claude -p}).
 *
 * <p>Uses whatever login the {@code claude} CLI has: a Pro/Max subscription, or an API key when
 * {@code ANTHROPIC_API_KEY} is set. Each run is isolated from the user's own Claude Code setup: no
 * user/project settings, CLAUDE.md, memory, skills or MCP servers other than repograph.
 */
public final class ClaudeCode {

  /** What one run may use. Tools not listed are unavailable; listed tools run without asking. */
  record Options(List<String> tools, boolean mayEdit, String jsonSchema, String resumeSessionId) {
  }

  /** Outcome of one run. {@code structured} is set when a JSON schema was requested. */
  record Reply(String text, JsonNode structured, String sessionId, int turns, double costUsd, long millis) {
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<String> REPOGRAPH_TOOLS = List.of("mcp__repograph__overview",
      "mcp__repograph__search_symbols", "mcp__repograph__find_usages", "mcp__repograph__impact_of_change",
      "mcp__repograph__trace_flow", "mcp__repograph__get_source");

  private final String executable;
  private final Path mcpConfig;
  private final String model;

  public ClaudeCode(String executable, Path mcpConfig, String model) {
    this.executable = executable;
    this.mcpConfig = mcpConfig;
    this.model = model;
  }

  Reply run(Path dir, String prompt, Options options) {
    List<String> command = new ArrayList<>(List.of(executable, "-p",
        "--output-format", "json",
        "--setting-sources", "",
        "--disable-slash-commands",
        "--strict-mcp-config", "--mcp-config", mcpConfig.toString(),
        "--tools", String.join(",", options.tools())));
    List<String> allowed = new ArrayList<>(options.tools());
    allowed.addAll(REPOGRAPH_TOOLS);
    command.addAll(List.of("--allowedTools", String.join(",", allowed)));
    if (options.mayEdit()) {
      // Edits are allowed inside the working directory only.
      command.addAll(List.of("--permission-mode", "acceptEdits"));
    }
    if (options.jsonSchema() != null) {
      command.addAll(List.of("--json-schema", options.jsonSchema()));
    }
    if (options.resumeSessionId() != null) {
      command.addAll(List.of("--resume", options.resumeSessionId()));
    }
    if (model != null) {
      command.addAll(List.of("--model", model));
    }

    long start = System.currentTimeMillis();
    Proc.Result result = Proc.run(dir, command, prompt, Map.of("CLAUDE_CODE_DISABLE_AUTO_MEMORY", "1"),
        Duration.ofMinutes(20));
    JsonNode json = parse(result);
    if (json.path("is_error").asBoolean(false) || !"success".equals(json.path("subtype").asString(""))) {
      throw new IllegalStateException("claude run failed (" + json.path("subtype").asString("?") + "): "
          + json.path("result").asString(""));
    }
    JsonNode structured = json.get("structured_output");
    return new Reply(json.path("result").asString(""), structured, json.path("session_id").asString(),
        json.path("num_turns").asInt(), json.path("total_cost_usd").asDouble(),
        System.currentTimeMillis() - start);
  }

  private static JsonNode parse(Proc.Result result) {
    // The JSON result is the last line; anything before it is CLI noise (warnings).
    String output = result.output().strip();
    String last = output.substring(output.lastIndexOf('\n') + 1);
    try {
      return JSON.readTree(last);
    } catch (RuntimeException e) {
      throw new IllegalStateException("claude exited " + result.exit() + " without a JSON result:\n"
          + result.tail(20), e);
    }
  }

  /** Serialises structured output into a record. */
  static <T> T convert(JsonNode node, Class<T> type) {
    return JSON.treeToValue(node, type);
  }
}
