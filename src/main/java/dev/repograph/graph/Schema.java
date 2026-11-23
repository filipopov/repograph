package dev.repograph.graph;

/** Node labels, relationship types and id conventions of the code graph. */
public final class Schema {

  public static final String REPO = "Repo";
  public static final String TYPE = "Type";
  public static final String FIELD = "Field";
  public static final String METHOD = "Method";
  public static final String ENDPOINT = "Endpoint";
  public static final String TOPIC = "Topic";

  /** Repo -> Type. */
  public static final String CONTAINS = "CONTAINS";
  /** Type -> Field. */
  public static final String HAS_FIELD = "HAS_FIELD";
  /** Type -> Method. */
  public static final String HAS_METHOD = "HAS_METHOD";
  /** Repo -> Repo, from Maven dependencies. */
  public static final String DEPENDS_ON = "DEPENDS_ON";
  /** Method -> Method. */
  public static final String CALLS = "CALLS";
  /** Method -> Field (accessor call, getter or field access). */
  public static final String READS = "READS";
  /** Method -> Type (constructor call). */
  public static final String CONSTRUCTS = "CONSTRUCTS";
  /** Method -> Type (parameter or return type, including generic arguments). */
  public static final String USES_TYPE = "USES_TYPE";
  /** Method -> Endpoint (server side). */
  public static final String EXPOSES = "EXPOSES";
  /** Method -> Endpoint (HTTP client side, cross-repo). */
  public static final String CALLS_HTTP = "CALLS_HTTP";
  /** Method -> Topic. */
  public static final String PUBLISHES = "PUBLISHES";
  /** Method -> Topic. */
  public static final String CONSUMES = "CONSUMES";
  /** Topic -> Type (payload). */
  public static final String CARRIES = "CARRIES";

  private Schema() {
  }

  public static String repoId(String repo) {
    return "repo:" + repo;
  }

  public static String fieldId(String typeFqn, String field) {
    return typeFqn + "#" + field;
  }

  public static String methodId(String typeFqn, String name, int arity) {
    return typeFqn + "#" + name + "(" + arity + ")";
  }

  public static String endpointId(String repo, String verb, String normalizedPath) {
    return "endpoint:" + repo + ":" + verb + " " + normalizedPath;
  }

  public static String topicId(String topic) {
    return "topic:" + topic;
  }
}
