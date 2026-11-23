package dev.repograph.mcp;

import dev.repograph.tools.GraphTools;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Exposes {@link GraphTools} to MCP clients: Claude Code / Desktop over stdio, ChatGPT and remote clients over HTTP. */
public final class RepographMcpServer {

  private static final String INSTRUCTIONS = """
      repograph is a pre-built code graph of a multi-repository Java microservice workspace: types,
      fields, methods, REST endpoints, HTTP clients and Kafka topics, linked ACROSS repositories
      (HTTP client -> endpoint in another repo, Kafka producer -> topic -> consumer).
      Prefer it over grep for cross-repo questions: grep cannot see that a client interface in one
      repo calls a controller in another, or that a Kafka event connects two services.
      Typical flow: overview -> search_symbols (to get exact names) -> impact_of_change / find_usages
      / trace_flow -> get_source to read the code before making claims. Cite repo and path:line.
      The graph is static analysis: it does not know runtime configuration (base URLs, feature flags)
      or consumers outside the workspace; say so when it matters.""";

  private static final String SYMBOL_HELP = "Accepted forms: 'PaymentDto', 'dev.demobank.contracts.PaymentDto', "
      + "'PaymentDto.amount', 'PaymentService#execute', 'POST /api/v1/payments', 'payments.completed', "
      + "or an exact id returned by another tool.";

  private RepographMcpServer() {
  }

  /** Serves the tools on stdio, for a local client that starts the process (Claude Code, Claude Desktop). */
  public static void runStdio(GraphTools tools, String version) throws InterruptedException {
    McpSyncServer server = register(McpServer.sync(new StdioServerTransportProvider(McpJsonDefaults.getMapper())),
        tools, version);
    Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully));
    Thread.currentThread().join();
  }

  /**
   * Serves the tools over MCP streamable HTTP at {@code /mcp}, for remote clients: ChatGPT apps and
   * connectors, Claude custom connectors, or anything behind a tunnel.
   */
  public static void runHttp(GraphTools tools, String version, HttpOptions options) throws Exception {
    HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
        .jsonMapper(McpJsonDefaults.getMapper())
        .mcpEndpoint("/mcp")
        .build();
    McpSyncServer server = register(McpServer.sync(transport), tools, version);
    HttpServer http = new HttpServer(transport, options);
    http.start();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      server.closeGracefully();
      http.stop();
    }));
    http.join();
  }

  /** Bind address, port and optional access token of the HTTP transport. */
  public record HttpOptions(String host, int port, String token) {
  }

  private static McpSyncServer register(McpServer.SyncSpecification<?> spec, GraphTools tools, String version) {
    spec.serverInfo("repograph", version);
    spec.instructions(INSTRUCTIONS);
    spec.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build());
    spec.toolCall(tool("overview",
            "Map of the whole workspace: repositories and their dependencies, every HTTP endpoint with its "
                + "handler and which repos call it, every Kafka topic with payload, producers and consumers. "
                + "Call this first.",
            Map.of()),
        handler(args -> tools.overview()));
    spec.toolCall(tool("search_symbols",
            "Find types, methods, fields, endpoints and topics whose name, id or file path contains the "
                + "query (case-insensitive). Use it to get exact names before calling other tools.",
            Map.of("query", string("Substring to search for, e.g. 'payment' or 'amount'")),
            "query"),
        handler(args -> tools.search(arg(args, "query"))));
    spec.toolCall(tool("find_usages",
            "Everything that references a symbol, across all repos: methods that read a field, construct or "
                + "use a type in a signature, call a method, or call an endpoint over HTTP. For a type, reads "
                + "of any of its fields are included. " + SYMBOL_HELP,
            Map.of("symbol", string("The symbol to look up"),
                "include_tests", Map.of("type", "boolean", "description", "Include test code (default false)")),
            "symbol"),
        handler(args -> tools.findUsages(arg(args, "symbol"), Boolean.TRUE.equals(args.get("include_tests")))));
    spec.toolCall(tool("impact_of_change",
            "What is affected if a field of a DTO, record or event changes (rename, type change, removal): "
                + "code references by repo, HTTP endpoints whose request/response carries the type (and who "
                + "calls them, or whether it looks like a public API), and Kafka topics carrying it. "
                + "Argument: 'Type.field', e.g. 'PaymentDto.amount'.",
            Map.of("field", string("Type.field, e.g. 'PaymentDto.amount'")),
            "field"),
        handler(args -> tools.impactOfChange(arg(args, "field"))));
    spec.toolCall(tool("trace_flow",
            "Follow a request downstream from an endpoint, method or topic: method calls, HTTP calls into "
                + "other repos and the handlers there, Kafka publishes and the consumers. Shows the end-to-end "
                + "path through the system with file locations. " + SYMBOL_HELP,
            Map.of("start", string("Where to start, e.g. 'POST /api/v1/payments'")),
            "start"),
        handler(args -> tools.traceFlow(arg(args, "start"))));
    spec.toolCall(tool("get_source",
            "Source code of a type, method or field, with line numbers. " + SYMBOL_HELP,
            Map.of("symbol", string("The symbol whose source to show")),
            "symbol"),
        handler(args -> tools.getSource(arg(args, "symbol"))));
    // search + fetch follow the shape ChatGPT expects from knowledge connectors (deep research,
    // company knowledge): search returns {results:[{id,title,url}]}, fetch returns one document.
    spec.toolCall(tool("search",
            "Search the code graph and return matching symbols as documents (id, title, url). "
                + "Pass an id to 'fetch' to read the symbol's source, usages and location.",
            Map.of("query", string("Search text, e.g. 'payment amount'")),
            "query"),
        handler(args -> tools.searchDocuments(arg(args, "query"))));
    spec.toolCall(tool("fetch",
            "Full document for an id returned by 'search': source code with line numbers and every "
                + "cross-repo usage.",
            Map.of("id", string("Document id from 'search'")),
            "id"),
        handler(args -> tools.fetchDocument(arg(args, "id"))));
    return spec.build();
  }

  private static McpSchema.Tool tool(String name, String description, Map<String, Object> properties,
      String... required) {
    return McpSchema.Tool.builder(name)
        .description(description)
        .inputSchema(Map.of("type", "object", "properties", properties, "required", List.of(required)))
        .annotations(new McpSchema.ToolAnnotations(null, true, false, true, false, null))
        .build();
  }

  private static Map<String, Object> string(String description) {
    return Map.of("type", "string", "description", description);
  }

  private static String arg(Map<String, Object> args, String name) {
    Object value = args == null ? null : args.get(name);
    if (!(value instanceof String s) || s.isBlank()) {
      throw new IllegalArgumentException("Missing argument '" + name + "'");
    }
    return s;
  }

  private static BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler(
      Function<Map<String, Object>, String> body) {
    return (exchange, request) -> {
      try {
        return CallToolResult.builder().addTextContent(body.apply(request.arguments())).build();
      } catch (RuntimeException e) {
        // Report tool errors to the model instead of failing the protocol call, so it can recover.
        return CallToolResult.builder().addTextContent("Error: " + e.getMessage()).isError(true).build();
      }
    };
  }
}
