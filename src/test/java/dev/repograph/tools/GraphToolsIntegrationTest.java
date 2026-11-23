package dev.repograph.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.repograph.Main;
import dev.repograph.store.Neo4jStore;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Runs the tools against the fixture workspace in a real Neo4j. The graph in that database is
 * REPLACED, so use a dedicated instance:
 *
 * <pre>
 * docker run -d --name repograph-test -p 7688:7687 -e NEO4J_AUTH=neo4j/repograph-test neo4j:5.26-community
 * REPOGRAPH_TEST_NEO4J_URI=bolt://localhost:7688 mvn test
 * </pre>
 *
 * Skipped when REPOGRAPH_TEST_NEO4J_URI is not set.
 */
class GraphToolsIntegrationTest {

  private static Neo4jStore store;
  private static GraphTools tools;

  @BeforeAll
  static void indexFixture() throws Exception {
    String uri = System.getenv("REPOGRAPH_TEST_NEO4J_URI");
    assumeTrue(uri != null && !uri.isBlank(), "REPOGRAPH_TEST_NEO4J_URI not set");
    store = new Neo4jStore(uri, "neo4j", System.getenv().getOrDefault("REPOGRAPH_TEST_NEO4J_PASSWORD", "repograph-test"));
    store.replace(Main.build(Path.of(GraphToolsIntegrationTest.class.getResource("/workspace").toURI())));
    tools = new GraphTools(store);
  }

  @AfterAll
  static void close() {
    if (store != null) {
      store.close();
    }
  }

  @Test
  void searchDocumentsMatchesEveryWordAndReturnsConnectorShape() {
    assertThat(tools.searchDocuments("order amount"))
        .startsWith("{\"results\":[")
        .contains("shop.contracts.OrderDto#amount", "\"url\":\"repograph://shop-contracts/")
        .doesNotContain("OrderPlacedEvent\"");
  }

  @Test
  void fetchDocumentReturnsSourceAndUsages() {
    assertThat(tools.fetchDocument("shop.contracts.OrderDto"))
        .contains("\"id\":\"shop.contracts.OrderDto\"", "record OrderDto", "Usages of shop.contracts.OrderDto");
  }

  @Test
  void overviewListsEndpointsCallersAndTopics() {
    assertThat(tools.overview())
        .containsPattern("orders-service\\s+GET\\s+/orders/\\{orderId}.*called by: \\[billing-service]")
        .containsPattern("orders-service\\s+POST\\s+/orders .*nobody in workspace")
        .containsPattern("orders.placed .*producers: \\[orders-service].*consumers: \\[billing-service]")
        .contains("HTTP client calls with no matching endpoint")
        .contains("/orders/archive/{id}");
  }

  @Test
  void impactOfChangeCoversCodeHttpAndCallers() {
    String impact = tools.impactOfChange("OrderDto.amount");

    assertThat(impact)
        .contains("Impact of changing shop.contracts.OrderDto#amount")
        .containsPattern("billing-service\\s+READS\\s+shop.billing.InvoiceService#onOrderPlaced\\(1\\)")
        .containsPattern("orders-service\\s+CONSTRUCTS\\s+shop.orders.OrderController#get\\(1\\)")
        .containsPattern("orders-service\\s+GET\\s+/orders/\\{orderId}\\s+called by: \\[billing-service]");
  }

  @Test
  void findUsagesOfControllerMethodIncludesCrossRepoHttpCallers() {
    assertThat(tools.findUsages("OrderController#get", false))
        .containsPattern("billing-service\\s+CALLS_HTTP GET /orders/\\{orderId}\\s+shop.billing.OrdersClient#get\\(1\\)");
  }

  @Test
  void findUsagesOfTypeIncludesFieldReads() {
    assertThat(tools.findUsages("OrderPlacedEvent", false))
        .contains("READS .orderId")
        .contains("CONSTRUCTS");
  }

  @Test
  void traceFlowCrossesHttpAndKafka() {
    String flow = tools.traceFlow("POST /orders");

    assertThat(flow)
        .contains("-HANDLED_BY-> shop.orders.OrderController#place(1)")
        .contains("-PUBLISHES-> topic:orders.placed")
        .contains("-CONSUMED_BY-> shop.billing.InvoiceService#onOrderPlaced(1)")
        .contains("-CALLS_HTTP-> endpoint:orders-service:GET /orders/{}");
  }

  @Test
  void getSourceReturnsNumberedLines() {
    assertThat(tools.getSource("OrderDto"))
        .contains("shop/contracts/OrderDto.java:5-6")
        .contains("   5  public record OrderDto(String id, BigDecimal amount, String customerId) {");
  }

  @Test
  void unknownSymbolSuggestsSimilarOnes() {
    assertThat(tools.impactOfChange("OrderDto.amout"))
        .contains("No symbol found for 'OrderDto.amout'");
    assertThat(tools.findUsages("OrderServic", false))
        .contains("Similar:")
        .contains("shop.orders.OrderService");
  }
}
