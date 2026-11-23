package dev.repograph.store;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** "What is affected if I change field X of type T?" answered from the graph. */
public final class ImpactQuery {

  /** Code that reads the field, constructs its type, or has the type in a signature. */
  static final String CODE = """
      MATCH (t:Type)-[:HAS_FIELD]->(f:Field {id: $fieldId})
      MATCH (m:Method)-[rel:READS|CONSTRUCTS|USES_TYPE]->(target)
      WHERE target = f OR target = t
      RETURN m.repo AS repo, m.id AS method, m.path AS path, m.line AS line,
             type(rel) AS how, m.test AS test
      ORDER BY test, repo, method, how
      """;

  /** Endpoints whose request or response contains the type, and which repos call them. */
  static final String HTTP = """
      MATCH (t:Type)-[:HAS_FIELD]->(:Field {id: $fieldId})
      MATCH (m:Method)-[:USES_TYPE]->(t)
      MATCH (m)-[:EXPOSES]->(e:Endpoint)
      OPTIONAL MATCH (caller:Method)-[:CALLS_HTTP]->(e)
      RETURN e.repo AS repo, e.verb AS verb, e.path AS path,
             [r IN collect(DISTINCT caller.repo) WHERE r IS NOT NULL] AS calledBy
      ORDER BY repo, path, verb
      """;

  /** Kafka topics whose payload is the type, with producing and consuming repos. */
  static final String KAFKA = """
      MATCH (t:Type)-[:HAS_FIELD]->(:Field {id: $fieldId})
      MATCH (topic:Topic)-[:CARRIES]->(t)
      OPTIONAL MATCH (p:Method)-[:PUBLISHES]->(topic)
      OPTIONAL MATCH (c:Method)-[:CONSUMES]->(topic)
      RETURN topic.name AS topic,
             [r IN collect(DISTINCT p.repo) WHERE r IS NOT NULL] AS producers,
             [r IN collect(DISTINCT c.repo) WHERE r IS NOT NULL] AS consumers
      """;

  static final String FIND_FIELD = """
      MATCH (t:Type)-[:HAS_FIELD]->(f:Field {name: $field})
      WHERE t.fqn = $type OR t.name = $type
      RETURN f.id AS id
      """;

  public record Result(String fieldId, List<Map<String, Object>> code, List<Map<String, Object>> http,
      List<Map<String, Object>> kafka) {
  }

  private final Neo4jStore store;

  public ImpactQuery(Neo4jStore store) {
    this.store = store;
  }

  /**
   * @param target {@code PaymentDto.amount}, {@code dev.demobank.contracts.PaymentDto.amount}
   *     or a field id {@code dev.demobank.contracts.PaymentDto#amount}
   */
  public Optional<Result> run(String target) {
    Optional<String> fieldId = resolveField(target);
    return fieldId.map(id -> {
      Map<String, Object> params = Map.of("fieldId", id);
      return new Result(id, store.query(CODE, params), store.query(HTTP, params), store.query(KAFKA, params));
    });
  }

  private Optional<String> resolveField(String target) {
    String normalized = target.replace('#', '.');
    int dot = normalized.lastIndexOf('.');
    if (dot <= 0) {
      throw new IllegalArgumentException("Expected Type.field, got " + target);
    }
    List<Map<String, Object>> matches = store.query(FIND_FIELD,
        Map.of("type", normalized.substring(0, dot), "field", normalized.substring(dot + 1)));
    if (matches.size() > 1) {
      throw new IllegalArgumentException("Ambiguous " + target + ", use the fully qualified type: "
          + matches.stream().map(m -> m.get("id")).toList());
    }
    return matches.stream().findFirst().map(m -> (String) m.get("id"));
  }
}
