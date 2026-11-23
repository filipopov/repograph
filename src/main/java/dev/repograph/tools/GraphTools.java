package dev.repograph.tools;

import dev.repograph.store.ImpactQuery;
import dev.repograph.store.Neo4jStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/**
 * The questions repograph can answer, rendered as compact text. Shared by the CLI and the MCP
 * server so that humans and agents see the same output.
 */
public final class GraphTools {

  private static final int MAX_SOURCE_LINES = 200;
  private static final int MAX_FLOW_DEPTH = 15;

  private static final String OVERVIEW_REPOS = """
      MATCH (r:Repo)
      OPTIONAL MATCH (r)-[:DEPENDS_ON]->(d:Repo)
      OPTIONAL MATCH (r)-[:CONTAINS]->(t:Type)
      RETURN r.name AS repo, collect(DISTINCT d.name) AS dependsOn, count(DISTINCT t) AS types
      ORDER BY repo
      """;
  private static final String OVERVIEW_ENDPOINTS = """
      MATCH (m:Method)-[:EXPOSES]->(e:Endpoint)
      OPTIONAL MATCH (c:Method)-[:CALLS_HTTP]->(e)
      RETURN e.repo AS repo, e.verb AS verb, e.path AS path, m.id AS handler,
             [r IN collect(DISTINCT c.repo) WHERE r IS NOT NULL] AS calledBy
      ORDER BY repo, path, verb
      """;
  private static final String OVERVIEW_TOPICS = """
      MATCH (t:Topic)
      OPTIONAL MATCH (p:Method)-[:PUBLISHES]->(t)
      OPTIONAL MATCH (c:Method)-[:CONSUMES]->(t)
      OPTIONAL MATCH (t)-[:CARRIES]->(payload:Type)
      RETURN t.name AS topic, collect(DISTINCT p.repo) AS producers, collect(DISTINCT c.repo) AS consumers,
             collect(DISTINCT payload.fqn) AS payload
      ORDER BY topic
      """;
  private static final String UNRESOLVED_HTTP = """
      MATCH (m:Method)-[:CALLS_HTTP]->(e:Endpoint {repo: 'external'})
      RETURN m.repo AS repo, e.verb AS verb, e.path AS path, m.id AS client
      """;

  private static final String SEARCH = """
      MATCH (n:Entity)
      WHERE (n:Type OR n:Method OR n:Field OR n:Endpoint OR n:Topic)
        AND (toLower(n.id) CONTAINS $q OR toLower(coalesce(n.name, '')) CONTAINS $q
             OR toLower(coalesce(n.path, '')) CONTAINS $q)
        AND coalesce(n.test, false) = false
      RETURN n.id AS id, labels(n) AS labels, n.repo AS repo, n.path AS path, n.line AS line
      ORDER BY size(n.id)
      LIMIT 40
      """;

  private static final String USAGES = """
      MATCH (target:Entity {id: $id})<-[r:READS|CONSTRUCTS|USES_TYPE|CALLS|CALLS_HTTP]-(m:Method)
      RETURN m.id AS method, m.repo AS repo, m.path AS path, m.line AS line, m.test AS test,
             type(r) + CASE WHEN r.inferred THEN ' (inferred)' ELSE '' END AS how
      UNION
      MATCH (:Type {id: $id})-[:HAS_FIELD]->(f:Field)<-[:READS]-(m:Method)
      RETURN m.id AS method, m.repo AS repo, m.path AS path, m.line AS line, m.test AS test,
             'READS .' + f.name AS how
      UNION
      MATCH (:Method {id: $id})-[:EXPOSES]->(e:Endpoint)<-[:CALLS_HTTP]-(m:Method)
      RETURN m.id AS method, m.repo AS repo, m.path AS path, m.line AS line, m.test AS test,
             'CALLS_HTTP ' + e.verb + ' ' + e.path AS how
      """;

  private static final String NEXT_HOPS = """
      MATCH (n:Entity {id: $id})
      CALL (n) {
        MATCH (n)-[r:CALLS|CALLS_HTTP|PUBLISHES]->(m) RETURN type(r) AS via, m
        UNION
        MATCH (n)<-[:EXPOSES]-(m) RETURN 'HANDLED_BY' AS via, m
        UNION
        MATCH (n)<-[:CONSUMES]-(m) RETURN 'CONSUMED_BY' AS via, m
      }
      WITH via, m WHERE coalesce(m.test, false) = false
      RETURN via, m.id AS id, labels(m) AS labels, m.repo AS repo, m.path AS path, m.line AS line
      ORDER BY id
      """;

  private static final String SOURCE = """
      MATCH (n:Entity {id: $id}), (r:Repo {name: n.repo})
      RETURN n.path AS path, n.line AS line, n.endLine AS endLine, r.dir AS dir
      """;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Neo4jStore store;
  private final SymbolResolver symbols;

  public GraphTools(Neo4jStore store) {
    this.store = store;
    this.symbols = new SymbolResolver(store);
  }

  public String overview() {
    StringBuilder out = new StringBuilder("Repositories\n");
    for (Map<String, Object> row : store.query(OVERVIEW_REPOS, Map.of())) {
      out.append("  %-24s %3s types  depends on: %s%n".formatted(row.get("repo"), row.get("types"),
          row.get("dependsOn")));
    }
    out.append("\nHTTP endpoints (server side)\n");
    for (Map<String, Object> row : store.query(OVERVIEW_ENDPOINTS, Map.of())) {
      List<?> callers = (List<?>) row.get("calledBy");
      out.append("  %-22s %-6s %-36s -> %s  called by: %s%n".formatted(row.get("repo"), row.get("verb"),
          row.get("path"), row.get("handler"), callers.isEmpty() ? "nobody in workspace (public API?)" : callers));
    }
    out.append("\nKafka topics\n");
    for (Map<String, Object> row : store.query(OVERVIEW_TOPICS, Map.of())) {
      out.append("  %-24s payload: %s  producers: %s  consumers: %s%n".formatted(row.get("topic"),
          row.get("payload"), row.get("producers"), row.get("consumers")));
    }
    List<Map<String, Object>> unresolved = store.query(UNRESOLVED_HTTP, Map.of());
    if (!unresolved.isEmpty()) {
      out.append("\nHTTP client calls with no matching endpoint in the workspace\n");
      unresolved.forEach(row -> out.append("  %-22s %-6s %-36s from %s%n".formatted(row.get("repo"),
          row.get("verb"), row.get("path"), row.get("client"))));
    }
    return out.toString();
  }

  public String search(String query) {
    List<Map<String, Object>> rows = store.query(SEARCH, Map.of("q", query.toLowerCase(Locale.ROOT)));
    if (rows.isEmpty()) {
      return "No symbols match '" + query + "'.";
    }
    StringBuilder out = new StringBuilder();
    rows.forEach(row -> out.append("%-9s %s  [%s %s%s]%n".formatted(((List<?>) row.get("labels")).stream()
            .map(String::valueOf).filter(l -> !"Entity".equals(l)).findFirst().orElse("?"),
        row.get("id"), row.get("repo"), row.get("path"), row.get("line") == null ? "" : ":" + row.get("line"))));
    return out.toString();
  }

  /** {@code search} in the shape ChatGPT connectors expect: {@code {"results":[{id,title,url}]}}. */
  public String searchDocuments(String query) {
    List<Map<String, Object>> results = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    // Every word must match on its own, so "payment amount" finds PaymentDto.amount.
    String[] words = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
    for (Map<String, Object> row : store.query(SEARCH, Map.of("q", words[0]))) {
      String id = (String) row.get("id");
      String haystack = (id + " " + row.get("path")).toLowerCase(Locale.ROOT);
      if (List.of(words).stream().allMatch(haystack::contains) && seen.add(id)) {
        results.add(Map.of("id", id, "title", kind(row) + " " + id + " (" + row.get("repo") + ")",
            "url", url(row)));
      }
    }
    return JSON.writeValueAsString(Map.of("results", results));
  }

  /** {@code fetch} in the shape ChatGPT connectors expect: one document with id, title, text, url. */
  public String fetchDocument(String id) {
    List<Map<String, Object>> rows = store.query(
        "MATCH (n:Entity {id: $id}) RETURN n.id AS id, labels(n) AS labels, n.repo AS repo, n.path AS path, "
            + "n.line AS line", Map.of("id", id));
    if (rows.isEmpty()) {
      throw new IllegalArgumentException("Unknown id '" + id + "'; use an id returned by search.");
    }
    Map<String, Object> row = rows.getFirst();
    String text = getSource(id) + "\n\n" + findUsages(id, false);
    return JSON.writeValueAsString(Map.of("id", id, "title", kind(row) + " " + id, "text", text,
        "url", url(row), "metadata", Map.of("repo", String.valueOf(row.get("repo")), "kind", kind(row))));
  }

  private static String kind(Map<String, Object> row) {
    return ((List<?>) row.get("labels")).stream().map(String::valueOf).filter(l -> !"Entity".equals(l))
        .findFirst().orElse("Symbol");
  }

  private static String url(Map<String, Object> row) {
    return "repograph://" + row.get("repo") + "/" + (row.get("path") == null ? "" : row.get("path"))
        + (row.get("line") == null ? "" : "#L" + row.get("line"));
  }

  public String findUsages(String symbol, boolean includeTests) {
    List<String> ids = symbols.resolve(symbol);
    if (ids.isEmpty()) {
      return notFound(symbol);
    }
    StringBuilder out = new StringBuilder();
    for (String id : ids) {
      List<Map<String, Object>> rows = store.query(USAGES, Map.of("id", id)).stream()
          .filter(row -> includeTests || !Boolean.TRUE.equals(row.get("test")))
          .sorted((a, b) -> (a.get("repo") + "" + a.get("method")).compareTo(b.get("repo") + "" + b.get("method")))
          .toList();
      out.append("Usages of ").append(id).append(" (").append(rows.size()).append(")\n");
      rows.forEach(row -> out.append("  %-22s %-28s %s%s  (%s:%s)%n".formatted(row.get("repo"), row.get("how"),
          row.get("method"), Boolean.TRUE.equals(row.get("test")) ? " [test]" : "", row.get("path"),
          row.get("line"))));
      out.append('\n');
    }
    return out.toString().stripTrailing();
  }

  public String impactOfChange(String field) {
    Optional<ImpactQuery.Result> result = new ImpactQuery(store).run(field);
    if (result.isEmpty()) {
      return notFound(field);
    }
    ImpactQuery.Result r = result.get();
    StringBuilder out = new StringBuilder("Impact of changing " + r.fieldId() + "\n\n");
    out.append("Code (").append(r.code().size()).append(" references)\n");
    for (Map<String, Object> row : r.code()) {
      out.append("  %-22s %-11s %s%s  (%s:%s)%n".formatted(row.get("repo"), row.get("how"), row.get("method"),
          Boolean.TRUE.equals(row.get("test")) ? " [test]" : "", row.get("path"), row.get("line")));
    }
    out.append("\nHTTP endpoints carrying the type\n");
    for (Map<String, Object> row : r.http()) {
      List<?> callers = (List<?>) row.get("calledBy");
      out.append("  %-22s %-6s %-30s called by: %s%n".formatted(row.get("repo"), row.get("verb"), row.get("path"),
          callers.isEmpty() ? "no repo in workspace (public API?)" : callers));
    }
    out.append("\nKafka topics carrying the type\n");
    for (Map<String, Object> row : r.kafka()) {
      out.append("  %-22s producers: %s  consumers: %s%n".formatted(row.get("topic"), row.get("producers"),
          row.get("consumers")));
    }
    return out.toString().stripTrailing();
  }

  /** Follows calls, HTTP hops and Kafka hops downstream from an endpoint, method or topic. */
  public String traceFlow(String start) {
    List<String> ids = symbols.resolve(start);
    if (ids.isEmpty()) {
      return notFound(start);
    }
    if (ids.size() > 1) {
      return "'" + start + "' is ambiguous, pick one:\n  " + String.join("\n  ", ids);
    }
    StringBuilder out = new StringBuilder(ids.getFirst()).append('\n');
    Set<String> visited = new HashSet<>(List.of(ids.getFirst()));
    Deque<Object[]> stack = new ArrayDeque<>();
    pushHops(stack, ids.getFirst(), 1);
    while (!stack.isEmpty()) {
      Object[] entry = stack.pop();
      @SuppressWarnings("unchecked")
      Map<String, Object> hop = (Map<String, Object>) entry[0];
      int depth = (int) entry[1];
      String id = (String) hop.get("id");
      boolean seen = !visited.add(id);
      out.append("  ".repeat(depth)).append("-").append(hop.get("via")).append("-> ").append(id);
      if (hop.get("line") != null) {
        out.append("  [").append(hop.get("repo")).append(' ').append(hop.get("path")).append(':')
            .append(hop.get("line")).append(']');
      }
      out.append(seen ? "  (see above)" : "").append('\n');
      if (!seen && depth < MAX_FLOW_DEPTH) {
        pushHops(stack, id, depth + 1);
      }
    }
    return out.toString().stripTrailing();
  }

  private void pushHops(Deque<Object[]> stack, String id, int depth) {
    List<Map<String, Object>> hops = store.query(NEXT_HOPS, Map.of("id", id));
    for (int i = hops.size() - 1; i >= 0; i--) {
      stack.push(new Object[] {hops.get(i), depth});
    }
  }

  public String getSource(String symbol) {
    List<String> ids = symbols.resolve(symbol);
    if (ids.isEmpty()) {
      return notFound(symbol);
    }
    StringBuilder out = new StringBuilder();
    for (String id : ids) {
      List<Map<String, Object>> rows = store.query(SOURCE, Map.of("id", id));
      if (rows.isEmpty() || rows.getFirst().get("path") == null) {
        out.append(id).append(": no source location\n\n");
        continue;
      }
      Map<String, Object> row = rows.getFirst();
      Path file = Path.of((String) row.get("dir")).resolve((String) row.get("path"));
      int from = ((Number) row.get("line")).intValue();
      int to = Math.min(((Number) row.get("endLine")).intValue(), from + MAX_SOURCE_LINES - 1);
      out.append("// ").append(id).append("  (").append(row.get("path")).append(':').append(from).append('-')
          .append(to).append(")\n");
      try {
        List<String> lines = Files.readAllLines(file);
        for (int n = from; n <= Math.min(to, lines.size()); n++) {
          out.append("%4d  %s%n".formatted(n, lines.get(n - 1)));
        }
      } catch (IOException e) {
        out.append("Cannot read ").append(file).append(": ").append(e.getMessage()).append('\n');
      }
      out.append('\n');
    }
    return out.toString().stripTrailing();
  }

  private String notFound(String symbol) {
    List<String> suggestions = new ArrayList<>();
    String last = symbol.replaceAll(".*[.#/ ]", "");
    if (last.length() >= 3) {
      store.query(SEARCH, Map.of("q", last.toLowerCase(Locale.ROOT))).stream().limit(8)
          .forEach(row -> suggestions.add((String) row.get("id")));
    }
    return "No symbol found for '" + symbol + "'."
        + (suggestions.isEmpty() ? "" : " Similar:\n  " + String.join("\n  ", suggestions));
  }
}
