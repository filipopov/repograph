package dev.repograph;

import static org.assertj.core.api.Assertions.assertThat;

import dev.repograph.graph.CodeGraph;
import dev.repograph.graph.Schema;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Builds the graph for the fixture workspace (shop-contracts, orders-service, billing-service). */
class MainTest {

  private static CodeGraph graph;

  @BeforeAll
  static void buildGraph() throws URISyntaxException {
    graph = Main.build(Path.of(MainTest.class.getResource("/workspace").toURI()));
  }

  @Test
  void declaresTypesAndRecordComponents() {
    assertThat(graph.node("shop.contracts.OrderDto")).get()
        .satisfies(n -> assertThat(n.props()).containsEntry("kind", "record").containsEntry("repo", "shop-contracts"));
    assertThat(targets("shop.contracts.OrderDto", Schema.HAS_FIELD)).containsExactlyInAnyOrder(
        "shop.contracts.OrderDto#id", "shop.contracts.OrderDto#amount", "shop.contracts.OrderDto#customerId");
  }

  @Test
  void linksRepoDependenciesFromPoms() {
    assertThat(targets("repo:billing-service", Schema.DEPENDS_ON)).containsExactly("repo:shop-contracts");
  }

  @Test
  void extractsServerEndpointsWithClassLevelPrefix() {
    assertThat(targets("shop.orders.OrderController#get(1)", Schema.EXPOSES))
        .containsExactly("endpoint:orders-service:GET /orders/{}");
    assertThat(targets("shop.orders.OrderController#place(1)", Schema.EXPOSES))
        .containsExactly("endpoint:orders-service:POST /orders");
  }

  @Test
  void linksHttpClientToEndpointInOtherRepo() {
    assertThat(targets("shop.billing.OrdersClient#get(1)", Schema.CALLS_HTTP))
        .containsExactly("endpoint:orders-service:GET /orders/{}");
  }

  @Test
  void unmatchedClientCallPointsToExternalEndpoint() {
    assertThat(targets("shop.billing.OrdersClient#archived(1)", Schema.CALLS_HTTP))
        .containsExactly("endpoint:external:GET /orders/archive/{}");
  }

  @Test
  void detectsCrossRepoFieldReads() {
    assertThat(sources(Schema.READS, "shop.contracts.OrderDto#amount"))
        .containsExactly("shop.billing.InvoiceService#onOrderPlaced(1)");
    assertThat(sources(Schema.READS, "shop.contracts.OrderPlacedEvent#orderId"))
        .containsExactly("shop.billing.InvoiceService#onOrderPlaced(1)");
  }

  @Test
  void detectsConstructionAndSignatureUsage() {
    assertThat(sources(Schema.CONSTRUCTS, "shop.contracts.OrderDto"))
        .containsExactly("shop.orders.OrderController#get(1)");
    assertThat(sources(Schema.USES_TYPE, "shop.contracts.OrderDto")).containsExactlyInAnyOrder(
        "shop.orders.OrderController#get(1)", "shop.orders.OrderController#place(1)",
        "shop.orders.OrderService#place(1)", "shop.billing.OrdersClient#get(1)",
        "shop.billing.OrdersClient#archived(1)", "shop.billing.InvoiceService#reload(1)");
  }

  @Test
  void resolvesInternalMethodCalls() {
    assertThat(targets("shop.orders.OrderController#place(1)", Schema.CALLS))
        .contains("shop.orders.OrderService#place(1)");
    assertThat(targets("shop.billing.InvoiceService#onOrderPlaced(1)", Schema.CALLS))
        .contains("shop.billing.OrdersClient#get(1)");
  }

  @Test
  void infersCallTargetWhenAnArgumentHasAnUnknownLibraryType() {
    assertThat(graph.edges(Schema.CALLS)).anySatisfy(e -> {
      assertThat(e.from()).isEqualTo("shop.billing.InvoiceService#reload(1)");
      assertThat(e.to()).isEqualTo("shop.billing.OrdersClient#get(1)");
      assertThat(e.props()).containsEntry("inferred", true);
    });
  }

  @Test
  void linksKafkaProducerAndConsumerThroughConstantTopic() {
    assertThat(targets("shop.orders.OrderService#place(1)", Schema.PUBLISHES)).containsExactly("topic:orders.placed");
    assertThat(targets("shop.billing.InvoiceService#onOrderPlaced(1)", Schema.CONSUMES))
        .containsExactly("topic:orders.placed");
    assertThat(targets("topic:orders.placed", Schema.CARRIES)).containsExactly("shop.contracts.OrderPlacedEvent");
  }

  private static List<String> targets(String from, String type) {
    return graph.edges(type).stream().filter(e -> e.from().equals(from)).map(CodeGraph.Edge::to).toList();
  }

  private static List<String> sources(String type, String to) {
    return graph.edges(type).stream().filter(e -> e.to().equals(to)).map(CodeGraph.Edge::from).toList();
  }
}
