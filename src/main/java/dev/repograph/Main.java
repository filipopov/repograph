package dev.repograph;

import dev.repograph.agent.ChangeAgent;
import dev.repograph.agent.ClaudeCode;
import dev.repograph.extract.HttpLinker;
import dev.repograph.extract.JavaExtractor;
import dev.repograph.graph.CodeGraph;
import dev.repograph.mcp.RepographMcpServer;
import dev.repograph.scan.Repo;
import dev.repograph.scan.WorkspaceScanner;
import dev.repograph.store.Neo4jStore;
import dev.repograph.tools.GraphTools;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Command line entry point. Run without arguments for usage.
 *
 * <p>Neo4j connection: REPOGRAPH_NEO4J_URI, REPOGRAPH_NEO4J_USER, REPOGRAPH_NEO4J_PASSWORD.
 */
public final class Main {

  private static final String VERSION = "0.4.0";

  private Main() {
  }

  public static void main(String[] args) throws InterruptedException {
    if (args.length == 0) {
      usage();
    }
    String command = args[0];
    String arg = args.length > 1 ? String.join(" ", List.of(args).subList(1, args.length)) : null;
    switch (command) {
      case "index" -> index(Path.of(required(arg)).toAbsolutePath().normalize());
      case "serve" -> serve(List.of(args).subList(1, args.length));
      case "overview" -> withTools(GraphTools::overview);
      case "search" -> withTools(tools -> tools.search(required(arg)));
      case "usages" -> withTools(tools -> tools.findUsages(required(arg), true));
      case "impact" -> withTools(tools -> tools.impactOfChange(required(arg)));
      case "flow" -> withTools(tools -> tools.traceFlow(required(arg)));
      case "source" -> withTools(tools -> tools.getSource(required(arg)));
      case "change" -> change(List.of(args).subList(1, args.length));
      default -> usage();
    }
  }

  /** Builds the graph without persisting it; also used by tests. */
  public static CodeGraph build(Path workspace) {
    List<Repo> repos = WorkspaceScanner.scan(workspace);
    CodeGraph graph = new CodeGraph();
    JavaExtractor extractor = new JavaExtractor(graph);
    extractor.extract(repos);
    HttpLinker.link(graph, extractor.clientCalls());
    JavaExtractor.Stats stats = extractor.stats();
    System.out.printf("Parsed %d files in %d repos (%d parse errors); calls resolved %d, unresolved (library) %d%n",
        stats.files(), repos.size(), stats.parseErrors(), stats.resolvedCalls(), stats.unresolvedCalls());
    return graph;
  }

  private static void index(Path workspace) {
    CodeGraph graph = build(workspace);
    Map<String, Long> nodes = new TreeMap<>(graph.nodes().stream()
        .collect(Collectors.groupingBy(CodeGraph.Node::label, Collectors.counting())));
    Map<String, Long> edges = new TreeMap<>(graph.edges().stream()
        .collect(Collectors.groupingBy(CodeGraph.Edge::type, Collectors.counting())));
    System.out.println("Nodes: " + nodes);
    System.out.println("Edges: " + edges);
    try (Neo4jStore store = store()) {
      store.replace(graph);
    }
    System.out.println("Graph written to Neo4j.");
  }

  /** {@code serve [--http [port]] [--host h]}: stdio by default (stdout is the MCP channel). */
  private static void serve(List<String> args) throws InterruptedException {
    GraphTools tools = new GraphTools(store());
    int http = args.indexOf("--http");
    if (http < 0) {
      RepographMcpServer.runStdio(tools, VERSION);
      return;
    }
    int port = http + 1 < args.size() && args.get(http + 1).matches("\\d+")
        ? Integer.parseInt(args.get(http + 1)) : Integer.parseInt(env("REPOGRAPH_HTTP_PORT", "8765"));
    int hostIndex = args.indexOf("--host");
    String host = hostIndex >= 0 && hostIndex + 1 < args.size() ? args.get(hostIndex + 1)
        : env("REPOGRAPH_HTTP_HOST", "127.0.0.1");
    try {
      RepographMcpServer.runHttp(tools, VERSION,
          new RepographMcpServer.HttpOptions(host, port, System.getenv("REPOGRAPH_HTTP_TOKEN")));
    } catch (InterruptedException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Cannot start HTTP server on " + host + ":" + port, e);
    }
  }

  /** {@code change <workspace> [--dry-run] <request...>}: plan, edit, verify, open PRs. */
  private static void change(List<String> args) {
    boolean dryRun = args.contains("--dry-run");
    List<String> rest = args.stream().filter(a -> !a.equals("--dry-run")).toList();
    if (rest.size() < 2) {
      usage();
    }
    Path workspace = Path.of(rest.getFirst()).toAbsolutePath().normalize();
    String request = String.join(" ", rest.subList(1, rest.size()));
    index(workspace);
    try (Neo4jStore store = store()) {
      ChangeAgent agent = new ChangeAgent(workspace, WorkspaceScanner.scan(workspace), new GraphTools(store),
          new ClaudeCode(env("REPOGRAPH_CLAUDE", "claude"), mcpConfig(), System.getenv("REPOGRAPH_MODEL")),
          env("REPOGRAPH_MVN", "mvn"), !dryRun, System.out::println);
      System.out.println("Report: " + agent.run(request));
    }
  }

  /** MCP config that starts this same jar as the repograph server for the model. */
  private static Path mcpConfig() {
    try {
      String java = ProcessHandle.current().info().command().orElse("java");
      String jar = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
      Path file = Files.createTempFile("repograph-mcp", ".json");
      file.toFile().deleteOnExit();
      Files.writeString(file, """
          {"mcpServers": {"repograph": {"command": "%s", "args": ["-jar", "%s", "serve"]}}}"""
          .formatted(java, jar));
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (java.net.URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void withTools(java.util.function.Function<GraphTools, String> action) {
    try (Neo4jStore store = store()) {
      System.out.println(action.apply(new GraphTools(store)));
    }
  }

  private static Neo4jStore store() {
    return new Neo4jStore(
        env("REPOGRAPH_NEO4J_URI", "bolt://localhost:7687"),
        env("REPOGRAPH_NEO4J_USER", "neo4j"),
        env("REPOGRAPH_NEO4J_PASSWORD", "repograph-dev"));
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String required(String arg) {
    if (arg == null || arg.isBlank()) {
      usage();
    }
    return arg;
  }

  private static void usage() {
    System.err.println("""
        Usage:
          repograph index <workspace-dir>   parse all Maven repos in the directory and write the graph to Neo4j
          repograph serve                   run the MCP server on stdio
          repograph serve --http [port]     run it over streamable HTTP at /mcp (default 127.0.0.1:8765;
                                            REPOGRAPH_HTTP_TOKEN requires a bearer or ?token= value)
          repograph overview                repos, endpoints and topics
          repograph search <text>           find symbols
          repograph usages <symbol>         everything referencing a symbol
          repograph impact <Type.field>     what is affected by changing a field
          repograph flow <start>            trace a request downstream, e.g. "POST /api/v1/payments"
          repograph source <symbol>         show source code
          repograph change <workspace-dir> [--dry-run] <request>
                                            plan a change with the model, edit and build every affected
                                            repo, open linked PRs (--dry-run: local commits only)""");
    System.exit(2);
  }
}
