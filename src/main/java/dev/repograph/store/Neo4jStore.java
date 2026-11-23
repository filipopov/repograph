package dev.repograph.store;

import dev.repograph.graph.CodeGraph;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;

/**
 * Persists a {@link CodeGraph} to Neo4j. Every node gets the extra label {@code Entity} with a
 * unique {@code id}, which keeps edge writes label-agnostic.
 */
public final class Neo4jStore implements AutoCloseable {

  private static final int BATCH = 1000;

  private final Driver driver;

  public Neo4jStore(String uri, String user, String password) {
    this.driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
    driver.verifyConnectivity();
  }

  /** Replaces the whole graph. */
  public void replace(CodeGraph graph) {
    try (Session session = driver.session()) {
      session.run("CREATE CONSTRAINT entity_id IF NOT EXISTS FOR (n:Entity) REQUIRE n.id IS UNIQUE").consume();
      session.executeWrite(tx -> tx.run("MATCH (n:Entity) DETACH DELETE n").consume());

      Map<String, List<CodeGraph.Node>> nodesByLabel =
          graph.nodes().stream().collect(Collectors.groupingBy(CodeGraph.Node::label));
      nodesByLabel.forEach((label, nodes) -> {
        String cypher = "UNWIND $rows AS row MERGE (n:Entity {id: row.id}) SET n:" + label + ", n += row.props";
        List<Map<String, Object>> rows = nodes.stream()
            .map(n -> Map.<String, Object>of("id", n.id(), "props", n.props()))
            .toList();
        writeBatched(session, cypher, rows);
      });

      Map<String, List<CodeGraph.Edge>> edgesByType =
          graph.edges().stream().collect(Collectors.groupingBy(CodeGraph.Edge::type));
      edgesByType.forEach((type, edges) -> {
        String cypher = "UNWIND $rows AS row MATCH (a:Entity {id: row.from}) MATCH (b:Entity {id: row.to}) "
            + "MERGE (a)-[r:" + type + "]->(b) SET r += row.props";
        List<Map<String, Object>> rows = edges.stream()
            .map(e -> Map.<String, Object>of("from", e.from(), "to", e.to(), "props", e.props()))
            .toList();
        writeBatched(session, cypher, rows);
      });
    }
  }

  public List<Map<String, Object>> query(String cypher, Map<String, Object> params) {
    return driver.executableQuery(cypher).withParameters(params).execute().records().stream()
        .map(Neo4jStore::toMap)
        .toList();
  }

  private static Map<String, Object> toMap(Record record) {
    Map<String, Object> map = new HashMap<>();
    record.keys().forEach(key -> map.put(key, record.get(key).asObject()));
    return map;
  }

  private static void writeBatched(Session session, String cypher, List<Map<String, Object>> rows) {
    for (int i = 0; i < rows.size(); i += BATCH) {
      List<Map<String, Object>> batch = rows.subList(i, Math.min(i + BATCH, rows.size()));
      session.executeWrite(tx -> tx.run(cypher, Map.of("rows", batch)).consume());
    }
  }

  @Override
  public void close() {
    driver.close();
  }
}
