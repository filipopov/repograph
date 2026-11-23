package dev.repograph.mcp;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumSet;
import org.eclipse.jetty.ee11.servlet.FilterHolder;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

/**
 * Embedded Jetty serving the MCP endpoint ({@code /mcp}) and a health check ({@code /health}).
 *
 * <p>Binds to localhost by default; expose it with a tunnel. When a token is configured, {@code /mcp}
 * requires it as {@code Authorization: Bearer <token>} or as a {@code ?token=} query parameter (for
 * clients that only take a URL, such as a ChatGPT connector without OAuth).
 */
final class HttpServer {

  private final Server server;

  HttpServer(HttpServlet mcpServlet, RepographMcpServer.HttpOptions options) {
    server = new Server();
    ServerConnector connector = new ServerConnector(server);
    connector.setHost(options.host());
    connector.setPort(options.port());
    server.addConnector(connector);

    ServletContextHandler context = new ServletContextHandler();
    context.setContextPath("/");
    ServletHolder mcp = new ServletHolder("mcp", mcpServlet);
    mcp.setAsyncSupported(true);
    context.addServlet(mcp, "/mcp/*");
    context.addServlet(new ServletHolder("health", new HttpServlet() {
      @Override
      protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("text/plain");
        response.getWriter().write("ok");
      }
    }), "/health");
    if (options.token() != null && !options.token().isBlank()) {
      FilterHolder auth = new FilterHolder(new TokenFilter(options.token()));
      auth.setAsyncSupported(true);
      context.addFilter(auth, "/mcp/*", EnumSet.of(DispatcherType.REQUEST));
    }
    server.setHandler(context);
  }

  void start() throws Exception {
    server.start();
    // stderr only: some clients start the jar and read stdout.
    System.err.println("repograph MCP over HTTP: " + server.getURI().resolve("/mcp"));
  }

  void join() throws InterruptedException {
    server.join();
  }

  void stop() {
    try {
      server.stop();
    } catch (Exception e) {
      // Shutting down anyway; nothing else to release.
      System.err.println("Error stopping HTTP server: " + e.getMessage());
    }
  }

  /** Rejects requests without the configured token. Constant-time comparison. */
  static final class TokenFilter implements Filter {

    private final byte[] expected;

    TokenFilter(String token) {
      this.expected = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
        throws IOException, ServletException {
      HttpServletRequest request = (HttpServletRequest) req;
      String header = request.getHeader("Authorization");
      String given = header != null && header.startsWith("Bearer ") ? header.substring(7)
          : request.getParameter("token");
      if (given != null && MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8))) {
        chain.doFilter(req, res);
        return;
      }
      HttpServletResponse response = (HttpServletResponse) res;
      response.setHeader("WWW-Authenticate", "Bearer");
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }
  }
}
