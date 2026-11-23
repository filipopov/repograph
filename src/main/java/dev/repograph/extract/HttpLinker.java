package dev.repograph.extract;

import static dev.repograph.graph.Schema.CALLS_HTTP;
import static dev.repograph.graph.Schema.ENDPOINT;

import dev.repograph.graph.CodeGraph;
import dev.repograph.graph.Schema;
import java.util.List;
import java.util.Map;

/**
 * Links HTTP client methods to the server endpoints they call, across repos.
 *
 * <p>Matching is by verb and normalised path. Base URLs come from runtime configuration, so a call
 * is linked to every other repo exposing a matching endpoint; more than one match is flagged.
 * Calls with no match get a placeholder endpoint in repo {@code external}.
 */
public final class HttpLinker {

  private HttpLinker() {
  }

  public static void link(CodeGraph graph, List<JavaExtractor.ClientCall> calls) {
    List<CodeGraph.Node> endpoints = graph.nodes(ENDPOINT);
    for (JavaExtractor.ClientCall call : calls) {
      String normalized = HttpPaths.normalize(call.path());
      List<CodeGraph.Node> matches = endpoints.stream()
          .filter(e -> !e.props().get("repo").equals(call.repo()))
          .filter(e -> e.props().get("verb").equals(call.verb()))
          .filter(e -> e.props().get("normalizedPath").equals(normalized))
          .toList();
      if (matches.isEmpty()) {
        String id = Schema.endpointId("external", call.verb(), normalized);
        graph.addNode(id, ENDPOINT, Map.of("repo", "external", "verb", call.verb(), "path", call.path(),
            "normalizedPath", normalized));
        graph.addEdge(call.methodId(), CALLS_HTTP, id, Map.of("resolved", false));
        continue;
      }
      for (CodeGraph.Node endpoint : matches) {
        graph.addEdge(call.methodId(), CALLS_HTTP, endpoint.id(),
            Map.of("resolved", true, "ambiguous", matches.size() > 1));
      }
    }
  }
}
