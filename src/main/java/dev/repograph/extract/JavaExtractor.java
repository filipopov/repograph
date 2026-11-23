package dev.repograph.extract;

import static dev.repograph.graph.Schema.CALLS;
import static dev.repograph.graph.Schema.CARRIES;
import static dev.repograph.graph.Schema.CONSTRUCTS;
import static dev.repograph.graph.Schema.CONSUMES;
import static dev.repograph.graph.Schema.CONTAINS;
import static dev.repograph.graph.Schema.DEPENDS_ON;
import static dev.repograph.graph.Schema.ENDPOINT;
import static dev.repograph.graph.Schema.EXPOSES;
import static dev.repograph.graph.Schema.FIELD;
import static dev.repograph.graph.Schema.HAS_FIELD;
import static dev.repograph.graph.Schema.HAS_METHOD;
import static dev.repograph.graph.Schema.METHOD;
import static dev.repograph.graph.Schema.PUBLISHES;
import static dev.repograph.graph.Schema.READS;
import static dev.repograph.graph.Schema.REPO;
import static dev.repograph.graph.Schema.TOPIC;
import static dev.repograph.graph.Schema.TYPE;
import static dev.repograph.graph.Schema.USES_TYPE;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import dev.repograph.graph.CodeGraph;
import dev.repograph.graph.Schema;
import dev.repograph.scan.Repo;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Parses all Java sources of a workspace and fills a {@link CodeGraph}.
 *
 * <p>Spring and Kafka are recognised by annotation and type <em>names</em>, so library jars are
 * not needed on the classpath. Types are resolved across repos through their source roots.
 */
public final class JavaExtractor {

  private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of("RestController", "Controller");
  private static final Map<String, String> SERVER_MAPPINGS = Map.of(
      "GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT",
      "DeleteMapping", "DELETE", "PatchMapping", "PATCH");
  private static final Map<String, String> CLIENT_MAPPINGS = Map.of(
      "GetExchange", "GET", "PostExchange", "POST", "PutExchange", "PUT",
      "DeleteExchange", "DELETE", "PatchExchange", "PATCH");

  /** An HTTP client call found in an {@code @HttpExchange} interface, linked by {@link HttpLinker}. */
  public record ClientCall(String methodId, String repo, String verb, String path) {
  }

  /** Counts of what could and could not be resolved, for diagnostics. */
  public record Stats(int files, int parseErrors, int resolvedCalls, int unresolvedCalls) {
  }

  private record Source(Repo repo, boolean test, Path file, CompilationUnit cu) {
  }

  private final CodeGraph graph;
  private final List<ClientCall> clientCalls = new ArrayList<>();
  private final Map<String, String> constants = new HashMap<>();
  private int parseErrors;
  private int resolvedCalls;
  private int unresolvedCalls;
  private int files;

  public JavaExtractor(CodeGraph graph) {
    this.graph = graph;
  }

  public List<ClientCall> clientCalls() {
    return clientCalls;
  }

  public Stats stats() {
    return new Stats(files, parseErrors, resolvedCalls, unresolvedCalls);
  }

  public void extract(List<Repo> repos) {
    ParserConfiguration config = new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_25);
    config.setSymbolResolver(new JavaSymbolSolver(typeSolver(repos, config)));
    JavaParser parser = new JavaParser(config);

    List<Source> sources = new ArrayList<>();
    for (Repo repo : repos) {
      graph.addNode(Schema.repoId(repo.name()), REPO,
          Map.of("name", repo.name(), "dir", repo.dir().toAbsolutePath().toString()));
      repo.mainRoots().forEach(root -> parseAll(parser, repo, false, root, sources));
      repo.testRoots().forEach(root -> parseAll(parser, repo, true, root, sources));
    }
    linkRepoDependencies(repos);

    sources.forEach(this::declare);
    sources.forEach(this::analyse);
  }

  // ---------------------------------------------------------------- parsing

  /**
   * The source-root solvers must parse with the same configuration (including the symbol resolver),
   * otherwise expressions inside types they load cannot be resolved.
   */
  private static CombinedTypeSolver typeSolver(List<Repo> repos, ParserConfiguration config) {
    CombinedTypeSolver solver = new CombinedTypeSolver(new ReflectionTypeSolver(true));
    for (Repo repo : repos) {
      Stream.concat(repo.mainRoots().stream(), repo.testRoots().stream())
          .forEach(root -> solver.add(new JavaParserTypeSolver(root, config)));
    }
    return solver;
  }

  private void parseAll(JavaParser parser, Repo repo, boolean test, Path root, List<Source> out) {
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path file : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
        files++;
        ParseResult<CompilationUnit> result = parser.parse(file);
        if (result.isSuccessful() && result.getResult().isPresent()) {
          out.add(new Source(repo, test, file, result.getResult().get()));
        } else {
          parseErrors++;
          System.err.println("Parse error in " + file + ": " + result.getProblems());
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void linkRepoDependencies(List<Repo> repos) {
    Map<String, Repo> byArtifact = new HashMap<>();
    repos.forEach(r -> byArtifact.put(r.artifactId(), r));
    for (Repo repo : repos) {
      for (String dep : repo.dependencies()) {
        Repo target = byArtifact.get(dep);
        if (target != null && target != repo) {
          graph.addEdge(Schema.repoId(repo.name()), DEPENDS_ON, Schema.repoId(target.name()));
        }
      }
    }
  }

  // ---------------------------------------------------------------- pass 1: declarations

  private void declare(Source src) {
    for (TypeDeclaration<?> type : src.cu().findAll(TypeDeclaration.class)) {
      Optional<String> fqn = type.getFullyQualifiedName();
      if (fqn.isEmpty()) {
        continue;
      }
      graph.addNode(fqn.get(), TYPE, props(src, type, Map.of(
          "name", type.getNameAsString(), "fqn", fqn.get(), "kind", kind(type))));
      graph.addEdge(Schema.repoId(src.repo().name()), CONTAINS, fqn.get());

      if (type.isRecordDeclaration()) {
        for (Parameter component : type.asRecordDeclaration().getParameters()) {
          addField(src, fqn.get(), component.getNameAsString(), component.getTypeAsString(), component);
        }
      }
      for (FieldDeclaration field : type.getFields()) {
        field.getVariables().forEach(v -> {
          addField(src, fqn.get(), v.getNameAsString(), v.getTypeAsString(), v);
          if (field.isStatic() && field.isFinal()) {
            v.getInitializer().flatMap(init -> stringValue(init, v)).ifPresent(value -> {
              constants.put(fqn.get() + "." + v.getNameAsString(), value);
              constants.putIfAbsent(type.getNameAsString() + "." + v.getNameAsString(), value);
            });
          }
        });
      }
      for (CallableDeclaration<?> callable : callables(type)) {
        String id = methodId(fqn.get(), callable);
        graph.addNode(id, METHOD, props(src, callable, Map.of(
            "name", callable.getNameAsString(),
            "signature", callable.getDeclarationAsString(false, false, true),
            "owner", fqn.get())));
        graph.addEdge(fqn.get(), HAS_METHOD, id);
      }
    }
  }

  private void addField(Source src, String typeFqn, String name, String type, Node node) {
    String id = Schema.fieldId(typeFqn, name);
    graph.addNode(id, FIELD, props(src, node, Map.of("name", name, "type", type, "owner", typeFqn)));
    graph.addEdge(typeFqn, HAS_FIELD, id);
  }

  // ---------------------------------------------------------------- pass 2: behaviour

  private void analyse(Source src) {
    for (TypeDeclaration<?> type : src.cu().findAll(TypeDeclaration.class)) {
      Optional<String> fqn = type.getFullyQualifiedName();
      if (fqn.isEmpty()) {
        continue;
      }
      String basePath = firstString(type, "RequestMapping", "value", "path").orElse("");
      boolean controller = hasAnyAnnotation(type, CONTROLLER_ANNOTATIONS);
      Optional<String> clientBase = type.getAnnotationByName("HttpExchange")
          .map(a -> annotationStrings(a, type, "value", "url").stream().findFirst().orElse(""));

      for (CallableDeclaration<?> callable : callables(type)) {
        String methodId = methodId(fqn.get(), callable);
        usesTypes(methodId, callable);
        analyseBody(methodId, callable, type);
        if (controller) {
          exposeEndpoint(src, methodId, callable, basePath);
        }
        clientBase.ifPresent(base -> recordClientCall(src, methodId, callable, base));
        kafkaListener(methodId, callable, type);
      }
    }
  }

  private void usesTypes(String methodId, CallableDeclaration<?> callable) {
    List<Type> types = new ArrayList<>();
    callable.getParameters().forEach(p -> types.add(p.getType()));
    if (callable instanceof MethodDeclaration md) {
      types.add(md.getType());
    }
    for (Type type : types) {
      for (ClassOrInterfaceType ref : type.findAll(ClassOrInterfaceType.class)) {
        resolveFqn(ref).filter(graph::hasNode).ifPresent(fqn -> graph.addEdge(methodId, USES_TYPE, fqn));
      }
    }
  }

  private void analyseBody(String methodId, CallableDeclaration<?> callable, TypeDeclaration<?> owner) {
    Node body = switch (callable) {
      case MethodDeclaration md -> md.getBody().orElse(null);
      case ConstructorDeclaration cd -> cd.getBody();
      default -> null;
    };
    if (body == null) {
      return;
    }
    for (ObjectCreationExpr creation : body.findAll(ObjectCreationExpr.class)) {
      resolveFqn(creation.getType()).filter(graph::hasNode)
          .ifPresent(fqn -> graph.addEdge(methodId, CONSTRUCTS, fqn));
    }
    for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
      if (!fieldRead(methodId, call)) {
        methodCall(methodId, call);
      }
      kafkaSend(methodId, call, owner);
    }
    for (MethodReferenceExpr ref : body.findAll(MethodReferenceExpr.class)) {
      Optional<String> scopeType = ref.getScope() instanceof TypeExpr typeExpr
          ? resolveFqn(typeExpr.getType())
          : expressionType(ref.getScope());
      scopeType.ifPresent(fqn -> {
        Optional<String> field = fieldFor(fqn, ref.getIdentifier());
        if (field.isPresent()) {
          graph.addEdge(methodId, READS, field.get());
        } else {
          methodTarget(fqn, ref.getIdentifier()).ifPresent(m -> graph.addEdge(methodId, CALLS, m));
        }
      });
    }
    for (FieldAccessExpr access : body.findAll(FieldAccessExpr.class)) {
      if (access.getScope() instanceof ThisExpr) {
        continue;
      }
      expressionType(access.getScope())
          .map(fqn -> Schema.fieldId(fqn, access.getNameAsString()))
          .filter(graph::hasNode)
          .ifPresent(field -> graph.addEdge(methodId, READS, field));
    }
  }

  /** {@code order.amount()} on a record, or {@code order.getAmount()} on a class. */
  private boolean fieldRead(String methodId, MethodCallExpr call) {
    if (!call.getArguments().isEmpty() || call.getScope().isEmpty()) {
      return false;
    }
    Optional<String> field = expressionType(call.getScope().get())
        .flatMap(fqn -> fieldFor(fqn, call.getNameAsString()));
    field.ifPresent(f -> graph.addEdge(methodId, READS, f));
    return field.isPresent();
  }

  private void methodCall(String methodId, MethodCallExpr call) {
    try {
      ResolvedMethodDeclaration target = call.resolve();
      String targetId = Schema.methodId(target.declaringType().getQualifiedName(), target.getName(),
          target.getNumberOfParams());
      if (graph.hasNode(targetId)) {
        graph.addEdge(methodId, CALLS, targetId);
      }
      resolvedCalls++;
    } catch (RuntimeException e) {
      // Overload resolution fails when an argument has a library type (e.g. Mockito's any()).
      // Fall back to receiver type + name + arity; anything else is a library call.
      Optional<String> inferred = inferCallTarget(call);
      if (inferred.isPresent()) {
        graph.addEdge(methodId, CALLS, inferred.get(), Map.of("inferred", true));
        resolvedCalls++;
        return;
      }
      if (System.getenv("REPOGRAPH_DEBUG") != null) {
        System.err.println("Unresolved call " + call + ": " + e.getMessage());
      }
      unresolvedCalls++;
    }
  }

  private Optional<String> inferCallTarget(MethodCallExpr call) {
    Optional<String> receiver = call.getScope().isPresent()
        ? expressionType(call.getScope().get())
        : call.findAncestor(TypeDeclaration.class).flatMap(TypeDeclaration::getFullyQualifiedName);
    return receiver
        .map(fqn -> Schema.methodId(fqn, call.getNameAsString(), call.getArguments().size()))
        .filter(graph::hasNode);
  }

  private Optional<String> fieldFor(String typeFqn, String accessor) {
    List<String> candidates = new ArrayList<>(List.of(accessor));
    for (String prefix : List.of("get", "is")) {
      if (accessor.length() > prefix.length() && accessor.startsWith(prefix)
          && Character.isUpperCase(accessor.charAt(prefix.length()))) {
        String rest = accessor.substring(prefix.length());
        candidates.add(Character.toLowerCase(rest.charAt(0)) + rest.substring(1));
      }
    }
    return candidates.stream().map(name -> Schema.fieldId(typeFqn, name)).filter(graph::hasNode).findFirst();
  }

  private Optional<String> methodTarget(String typeFqn, String name) {
    String prefix = typeFqn + "#" + name + "(";
    return graph.nodes(METHOD).stream().map(CodeGraph.Node::id).filter(id -> id.startsWith(prefix)).findFirst();
  }

  // ---------------------------------------------------------------- REST

  private void exposeEndpoint(Source src, String methodId, CallableDeclaration<?> callable, String basePath) {
    for (AnnotationExpr annotation : callable.getAnnotations()) {
      String name = annotation.getName().getIdentifier();
      String verb = SERVER_MAPPINGS.get(name);
      if (verb == null && name.equals("RequestMapping")) {
        verb = annotationStrings(annotation, callable, "method").stream().findFirst()
            .map(v -> v.substring(v.lastIndexOf('.') + 1)).orElse("ANY");
      }
      if (verb == null) {
        continue;
      }
      List<String> paths = annotationStrings(annotation, callable, "value", "path");
      for (String path : paths.isEmpty() ? List.of("") : paths) {
        String full = HttpPaths.join(basePath, path);
        String id = Schema.endpointId(src.repo().name(), verb, HttpPaths.normalize(full));
        graph.addNode(id, ENDPOINT, Map.of("repo", src.repo().name(), "verb", verb, "path", full,
            "normalizedPath", HttpPaths.normalize(full)));
        graph.addEdge(methodId, EXPOSES, id);
      }
    }
  }

  private void recordClientCall(Source src, String methodId, CallableDeclaration<?> callable, String basePath) {
    for (AnnotationExpr annotation : callable.getAnnotations()) {
      String name = annotation.getName().getIdentifier();
      String verb = CLIENT_MAPPINGS.get(name);
      if (verb == null && name.equals("HttpExchange")) {
        verb = annotationStrings(annotation, callable, "method").stream().findFirst().orElse("GET");
      }
      if (verb != null) {
        String path = annotationStrings(annotation, callable, "value", "url").stream().findFirst().orElse("");
        clientCalls.add(new ClientCall(methodId, src.repo().name(), verb.toUpperCase(Locale.ROOT),
            HttpPaths.join(basePath, path)));
      }
    }
  }

  // ---------------------------------------------------------------- Kafka

  private void kafkaListener(String methodId, CallableDeclaration<?> callable, TypeDeclaration<?> owner) {
    callable.getAnnotationByName("KafkaListener").ifPresent(annotation -> {
      Optional<String> payload = callable.getParameters().stream().findFirst()
          .flatMap(p -> payloadType(p.getType()));
      for (String topic : annotationStrings(annotation, owner, "topics", "value")) {
        String topicId = topic(topic);
        graph.addEdge(methodId, CONSUMES, topicId);
        payload.ifPresent(fqn -> graph.addEdge(topicId, CARRIES, fqn));
      }
    });
  }

  /** {@code kafkaTemplate.send(TOPIC, key, payload)} where the scope is a {@code KafkaTemplate} field. */
  private void kafkaSend(String methodId, MethodCallExpr call, TypeDeclaration<?> owner) {
    if (!call.getNameAsString().equals("send") || call.getArguments().size() < 2 || call.getScope().isEmpty()) {
      return;
    }
    String fieldName = switch (call.getScope().get()) {
      case NameExpr name -> name.getNameAsString();
      case FieldAccessExpr access when access.getScope() instanceof ThisExpr -> access.getNameAsString();
      default -> null;
    };
    if (fieldName == null) {
      return;
    }
    Optional<ClassOrInterfaceType> templateType = owner.getFieldByName(fieldName)
        .map(f -> f.getVariable(0).getType())
        .filter(Type::isClassOrInterfaceType)
        .map(Type::asClassOrInterfaceType)
        .filter(t -> t.getNameAsString().equals("KafkaTemplate"));
    if (templateType.isEmpty()) {
      return;
    }
    Optional<String> topic = stringValue(call.getArgument(0), owner);
    if (topic.isEmpty()) {
      return;
    }
    String topicId = topic(topic.get());
    graph.addEdge(methodId, PUBLISHES, topicId);
    templateType.get().getTypeArguments()
        .filter(args -> args.size() == 2)
        .flatMap(args -> payloadType(args.get(1)))
        .ifPresent(fqn -> graph.addEdge(topicId, CARRIES, fqn));
  }

  private Optional<String> payloadType(Type type) {
    if (!type.isClassOrInterfaceType()) {
      return Optional.empty();
    }
    ClassOrInterfaceType cit = type.asClassOrInterfaceType();
    if (cit.getNameAsString().equals("ConsumerRecord")) {
      return cit.getTypeArguments().filter(args -> args.size() == 2).flatMap(args -> payloadType(args.get(1)));
    }
    return resolveFqn(cit).filter(graph::hasNode);
  }

  private String topic(String name) {
    String id = Schema.topicId(name);
    graph.addNode(id, TOPIC, Map.of("name", name));
    return id;
  }

  // ---------------------------------------------------------------- annotation & constant helpers

  private Optional<String> firstString(NodeWithAnnotations<?> node, String annotation, String... keys) {
    return node.getAnnotationByName(annotation)
        .flatMap(a -> annotationStrings(a, (Node) node, keys).stream().findFirst());
  }

  private List<String> annotationStrings(AnnotationExpr annotation, Node context, String... keys) {
    List<Expression> values = new ArrayList<>();
    if (annotation.isSingleMemberAnnotationExpr()) {
      if (List.of(keys).contains("value")) {
        values.add(annotation.asSingleMemberAnnotationExpr().getMemberValue());
      }
    } else if (annotation.isNormalAnnotationExpr()) {
      annotation.asNormalAnnotationExpr().getPairs().stream()
          .filter(pair -> List.of(keys).contains(pair.getNameAsString()))
          .forEach(pair -> values.add(pair.getValue()));
    }
    List<String> result = new ArrayList<>();
    for (Expression value : values) {
      List<Expression> items = value.isArrayInitializerExpr()
          ? value.asArrayInitializerExpr().getValues()
          : List.of(value);
      items.forEach(item -> stringValue(item, context).ifPresent(result::add));
    }
    return result;
  }

  /** String literals, concatenations, constants ({@code Topics.X}, {@code X}) and enum names. */
  private Optional<String> stringValue(Expression expr, Node context) {
    if (expr.isStringLiteralExpr()) {
      return Optional.of(expr.asStringLiteralExpr().asString());
    }
    if (expr.isBinaryExpr()) {
      return stringValue(expr.asBinaryExpr().getLeft(), context)
          .flatMap(l -> stringValue(expr.asBinaryExpr().getRight(), context).map(r -> l + r));
    }
    if (expr instanceof FieldAccessExpr access) {
      String scope = access.getScope().toString();
      Optional<String> constant = Optional.ofNullable(constants.get(scope + "." + access.getNameAsString()));
      // RequestMethod.GET / HttpMethod.POST in mapping annotations: the enum constant name is the value.
      boolean httpMethodEnum = scope.endsWith("RequestMethod") || scope.endsWith("HttpMethod");
      return constant.or(() -> httpMethodEnum ? Optional.of(access.getNameAsString()) : Optional.empty());
    }
    if (expr instanceof NameExpr name) {
      Optional<String> enclosing = context.findAncestor(TypeDeclaration.class)
          .or(() -> context instanceof TypeDeclaration<?> t ? Optional.of(t) : Optional.empty())
          .flatMap(TypeDeclaration::getFullyQualifiedName);
      Optional<String> local = enclosing.map(fqn -> constants.get(fqn + "." + name.getNameAsString()));
      if (local.isPresent()) {
        return local;
      }
      List<String> matches = constants.entrySet().stream()
          .filter(e -> e.getKey().endsWith("." + name.getNameAsString()))
          .map(Map.Entry::getValue).distinct().toList();
      return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }
    return Optional.empty();
  }

  // ---------------------------------------------------------------- resolution helpers

  private Optional<String> resolveFqn(Type type) {
    try {
      ResolvedType resolved = type.resolve();
      return resolved.isReferenceType()
          ? Optional.of(resolved.asReferenceType().getQualifiedName())
          : Optional.empty();
    } catch (RuntimeException e) {
      // Library types (Spring, Kafka, ...) are not on the solver path; they are not graph nodes anyway.
      return Optional.empty();
    }
  }

  private Optional<String> expressionType(Expression expr) {
    try {
      ResolvedType resolved = expr.calculateResolvedType();
      return resolved.isReferenceType()
          ? Optional.of(resolved.asReferenceType().getQualifiedName())
          : Optional.empty();
    } catch (RuntimeException e) {
      // Same as above: expressions of library types cannot be resolved and are irrelevant here.
      return Optional.empty();
    }
  }

  private static List<CallableDeclaration<?>> callables(TypeDeclaration<?> type) {
    List<CallableDeclaration<?>> result = new ArrayList<>(type.getMethods());
    result.addAll(type.getConstructors());
    return result;
  }

  private static String methodId(String typeFqn, CallableDeclaration<?> callable) {
    String name = callable instanceof ConstructorDeclaration ? "<init>" : callable.getNameAsString();
    return Schema.methodId(typeFqn, name, callable.getParameters().size());
  }

  private static boolean hasAnyAnnotation(TypeDeclaration<?> type, Set<String> names) {
    return type.getAnnotations().stream().anyMatch(a -> names.contains(a.getName().getIdentifier()));
  }

  private static String kind(TypeDeclaration<?> type) {
    if (type.isRecordDeclaration()) {
      return "record";
    }
    if (type.isEnumDeclaration()) {
      return "enum";
    }
    if (type.isAnnotationDeclaration()) {
      return "annotation";
    }
    return type.asClassOrInterfaceDeclaration().isInterface() ? "interface" : "class";
  }

  private static Map<String, Object> props(Source src, Node node, Map<String, Object> extra) {
    Map<String, Object> props = new LinkedHashMap<>(extra);
    props.put("repo", src.repo().name());
    props.put("test", src.test());
    props.put("path", src.repo().dir().relativize(src.file()).toString());
    node.getBegin().ifPresent(pos -> props.put("line", pos.line));
    node.getEnd().ifPresent(pos -> props.put("endLine", pos.line));
    return props;
  }
}
