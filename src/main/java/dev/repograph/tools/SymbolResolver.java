package dev.repograph.tools;

import dev.repograph.extract.HttpPaths;
import dev.repograph.store.Neo4jStore;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns how people (and agents) name things into graph node ids.
 *
 * <ul>
 *   <li>an exact node id</li>
 *   <li>{@code POST /api/v1/payments} - endpoint(s) by verb and path</li>
 *   <li>{@code PaymentDto} or {@code dev.demobank.contracts.PaymentDto} - type</li>
 *   <li>{@code PaymentDto.amount}, {@code PaymentService#execute} - field or method(s)</li>
 *   <li>{@code payments.completed} - Kafka topic</li>
 * </ul>
 */
final class SymbolResolver {

  private static final Pattern ENDPOINT = Pattern.compile("(?i)(GET|POST|PUT|DELETE|PATCH|ANY)\\s+(/\\S*)");

  private static final String BY_ID = "MATCH (n:Entity {id: $id}) RETURN n.id AS id";
  private static final String ENDPOINTS = """
      MATCH (e:Endpoint {verb: $verb, normalizedPath: $path}) WHERE e.repo <> 'external'
      RETURN e.id AS id ORDER BY id
      """;
  private static final String TYPES = """
      MATCH (t:Type) WHERE t.name = $name OR t.fqn = $name RETURN t.id AS id ORDER BY id
      """;
  private static final String MEMBERS = """
      MATCH (t:Type)-[:HAS_FIELD|HAS_METHOD]->(m)
      WHERE (t.name = $type OR t.fqn = $type) AND m.name = $member
      RETURN m.id AS id ORDER BY id
      """;
  private static final String TOPICS = "MATCH (t:Topic {name: $name}) RETURN t.id AS id";

  private final Neo4jStore store;

  SymbolResolver(Neo4jStore store) {
    this.store = store;
  }

  List<String> resolve(String symbol) {
    String s = symbol.strip();
    List<String> ids = ids(BY_ID, Map.of("id", s));
    if (!ids.isEmpty()) {
      return ids;
    }
    Matcher endpoint = ENDPOINT.matcher(s);
    if (endpoint.matches()) {
      return ids(ENDPOINTS, Map.of("verb", endpoint.group(1).toUpperCase(Locale.ROOT),
          "path", HttpPaths.normalize(endpoint.group(2))));
    }
    ids = ids(TYPES, Map.of("name", s));
    if (!ids.isEmpty()) {
      return ids;
    }
    String member = s.replaceAll("\\(.*\\)$", "").replace('#', '.');
    int dot = member.lastIndexOf('.');
    if (dot > 0) {
      ids = ids(MEMBERS, Map.of("type", member.substring(0, dot), "member", member.substring(dot + 1)));
      if (!ids.isEmpty()) {
        return ids;
      }
    }
    return ids(TOPICS, Map.of("name", s));
  }

  private List<String> ids(String cypher, Map<String, Object> params) {
    return store.query(cypher, params).stream().map(row -> (String) row.get("id")).toList();
  }
}
