package dev.repograph.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Runs an external command (git, gh, mvn, claude) and captures its output. */
final class Proc {

  record Result(int exit, String output) {

    boolean ok() {
      return exit == 0;
    }

    /** The last {@code lines} lines of output, for error messages and build-failure feedback. */
    String tail(int lines) {
      List<String> all = output.lines().toList();
      return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
    }
  }

  private Proc() {
  }

  static Result run(Path dir, List<String> command) {
    return run(dir, command, null, Map.of(), Duration.ofMinutes(10));
  }

  /** Runs {@code command} in {@code dir}; stdout and stderr are merged. */
  static Result run(Path dir, List<String> command, String stdin, Map<String, String> env, Duration timeout) {
    ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
    builder.environment().putAll(env);
    try {
      Process process = builder.start();
      CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
        try {
          return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      });
      try (var in = process.getOutputStream()) {
        if (stdin != null) {
          in.write(stdin.getBytes(StandardCharsets.UTF_8));
        }
      }
      if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("Timed out after " + timeout + ": " + String.join(" ", command));
      }
      return new Result(process.exitValue(), output.join());
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot run " + command.getFirst(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted: " + String.join(" ", command), e);
    }
  }

  /** Like {@link #run(Path, List)} but fails with the command's output when it exits non-zero. */
  static String check(Path dir, String... command) {
    Result result = run(dir, List.of(command));
    if (!result.ok()) {
      throw new IllegalStateException(String.join(" ", command) + " failed in " + dir + ":\n" + result.tail(30));
    }
    return result.output().strip();
  }
}
