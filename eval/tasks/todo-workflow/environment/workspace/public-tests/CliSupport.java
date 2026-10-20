import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

final class CliSupport {
    record Result(int code, String output) {}
    static Result run(Path file, String... args) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            "-Dfile.encoding=UTF-8", "-cp", TodoClassPath.value(), "todo.TodoCli", file.toString()));
        command.addAll(List.of(args));
        Path output = Files.createTempFile(file.getParent(), "cli-", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "CLI timed out");
            return new Result(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8).replace("\r\n", "\n"));
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            Files.deleteIfExists(output);
        }
    }
    private static class TodoClassPath {
        static String value() throws Exception {
            return Path.of(todo.TodoCli.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        }
    }
}
