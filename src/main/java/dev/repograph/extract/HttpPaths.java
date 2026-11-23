package dev.repograph.extract;

/** Path helpers so that server mappings and client calls can be matched. */
public final class HttpPaths {

  private HttpPaths() {
  }

  public static String join(String base, String path) {
    String joined = "/" + base + "/" + path;
    joined = joined.replaceAll("/+", "/");
    return joined.length() > 1 && joined.endsWith("/") ? joined.substring(0, joined.length() - 1) : joined;
  }

  /** {@code /accounts/{id}/debit} and {@code /accounts/{accountId}/debit} both become {@code /accounts/{}/debit}. */
  public static String normalize(String path) {
    return join(path, "").replaceAll("\\{[^}]*}", "{}");
  }
}
