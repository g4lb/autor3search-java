package io.github.g4lb.autor3search;

import io.github.g4lb.autor3search.git.Git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A throwaway git repository for tests.
 *
 * <p>Every commit is made with an explicit identity and with commit hooks and
 * signing disabled, so the suite passes on a developer machine whose global git
 * configuration signs commits, on a CI runner with no identity configured at
 * all, and in a container with neither.
 */
public final class TestRepo {

    private final Path root;

    private TestRepo(Path root) {
        this.root = root;
    }

    public static TestRepo init(Path root) throws IOException {
        Files.createDirectories(root);
        Git.git(root, "init", "-q", "-b", "main");
        Git.git(root, "config", "user.name", "autor3search test");
        Git.git(root, "config", "user.email", "test@example.invalid");
        Git.git(root, "config", "commit.gpgsign", "false");
        Git.git(root, "config", "core.hooksPath", "/dev/null");
        return new TestRepo(root);
    }

    public Path root() {
        return root;
    }

    public TestRepo write(String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return this;
    }

    public String commit(String message) throws IOException {
        Git.git(root, "add", "-A");
        Git.git(root, "commit", "-q", "-m", message);
        return Git.headCommit(root);
    }
}
