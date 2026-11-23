package dev.repograph.graph;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** In-memory property graph built by the extractors and then persisted to Neo4j. */
public final class CodeGraph {

  public record Node(String id, String label, Map<String, Object> props) {
  }

  public record Edge(String from, String type, String to, Map<String, Object> props) {
  }

  private final Map<String, Node> nodes = new LinkedHashMap<>();
  private final Set<Edge> edges = new LinkedHashSet<>();

  /** Adds a node; the first definition of an id wins. */
  public void addNode(String id, String label, Map<String, Object> props) {
    nodes.putIfAbsent(id, new Node(id, label, Map.copyOf(props)));
  }

  public void addEdge(String from, String type, String to) {
    addEdge(from, type, to, Map.of());
  }

  public void addEdge(String from, String type, String to, Map<String, Object> props) {
    edges.add(new Edge(from, type, to, Map.copyOf(props)));
  }

  public boolean hasNode(String id) {
    return nodes.containsKey(id);
  }

  public Optional<Node> node(String id) {
    return Optional.ofNullable(nodes.get(id));
  }

  public Collection<Node> nodes() {
    return nodes.values();
  }

  public List<Node> nodes(String label) {
    return nodes.values().stream().filter(n -> n.label().equals(label)).toList();
  }

  public Set<Edge> edges() {
    return edges;
  }

  public List<Edge> edges(String type) {
    return edges.stream().filter(e -> e.type().equals(type)).toList();
  }
}
