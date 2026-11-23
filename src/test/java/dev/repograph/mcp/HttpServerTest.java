package dev.repograph.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpServerTest {

  private final HttpClient client = HttpClient.newHttpClient();
  private HttpServer server;
  private int port;

  @BeforeEach
  void start() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    HttpServlet echo = new HttpServlet() {
      @Override
      protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.getWriter().write("mcp");
      }
    };
    server = new HttpServer(echo, new RepographMcpServer.HttpOptions("127.0.0.1", port, "s3cret"));
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop();
  }

  @Test
  void healthNeedsNoToken() throws Exception {
    assertThat(get("/health").statusCode()).isEqualTo(200);
  }

  @Test
  void mcpRejectsMissingOrWrongToken() throws Exception {
    assertThat(post("/mcp", null).statusCode()).isEqualTo(401);
    assertThat(post("/mcp", "Bearer wrong").statusCode()).isEqualTo(401);
    assertThat(post("/mcp?token=wrong", null).statusCode()).isEqualTo(401);
  }

  @Test
  void mcpAcceptsBearerHeaderOrQueryToken() throws Exception {
    assertThat(post("/mcp", "Bearer s3cret").body()).isEqualTo("mcp");
    assertThat(post("/mcp?token=s3cret", null).body()).isEqualTo("mcp");
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String authorization) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.ofString("{}"));
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }
}
