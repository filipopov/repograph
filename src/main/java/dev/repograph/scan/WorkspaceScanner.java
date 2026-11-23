package dev.repograph.scan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Finds Maven repos (direct subdirectories with a pom.xml) in a workspace directory. */
public final class WorkspaceScanner {

  private WorkspaceScanner() {
  }

  public static List<Repo> scan(Path workspace) {
    try (Stream<Path> dirs = Files.list(workspace)) {
      return dirs.filter(dir -> Files.isRegularFile(dir.resolve("pom.xml")))
          .sorted()
          .map(WorkspaceScanner::readRepo)
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot list workspace " + workspace, e);
    }
  }

  private static Repo readRepo(Path dir) {
    Document pom = parse(dir.resolve("pom.xml"));
    Element project = pom.getDocumentElement();
    String artifactId = childText(project, "artifactId");
    List<String> dependencies = new ArrayList<>();
    Element deps = child(project, "dependencies");
    if (deps != null) {
      for (Node n = deps.getFirstChild(); n != null; n = n.getNextSibling()) {
        if (n instanceof Element dep && dep.getTagName().equals("dependency")) {
          dependencies.add(childText(dep, "artifactId"));
        }
      }
    }
    return new Repo(dir.getFileName().toString(), dir, artifactId, List.copyOf(dependencies),
        existing(dir.resolve("src/main/java")), existing(dir.resolve("src/test/java")));
  }

  private static List<Path> existing(Path path) {
    return Files.isDirectory(path) ? List.of(path) : List.of();
  }

  private static Document parse(Path pom) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      return factory.newDocumentBuilder().parse(pom.toFile());
    } catch (Exception e) {
      throw new IllegalStateException("Cannot parse " + pom, e);
    }
  }

  private static Element child(Element parent, String tag) {
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element e && e.getTagName().equals(tag)) {
        return e;
      }
    }
    return null;
  }

  private static String childText(Element parent, String tag) {
    Element e = child(parent, tag);
    return e == null ? null : e.getTextContent().trim();
  }
}
