package dev.repograph.scan;

import java.nio.file.Path;
import java.util.List;

/**
 * A Maven repository in the workspace.
 *
 * @param name directory name
 * @param artifactId Maven artifactId, used to link repo-to-repo dependencies
 * @param dependencies artifactIds of direct Maven dependencies
 */
public record Repo(String name, Path dir, String artifactId, List<String> dependencies,
    List<Path> mainRoots, List<Path> testRoots) {
}
