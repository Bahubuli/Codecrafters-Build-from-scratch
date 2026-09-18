import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Pattern;

public class Main {
    private static final Set<String> BUILTINS = Set.of("echo", "exit", "type", "pwd", "cd");
    private static Path currentDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            if (!scanner.hasNextLine()) {
                break;
            }
            String input = scanner.nextLine();
            String trimmed = input.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            String command = parts[0];

            if (command.equals("exit")) {
                int exitCode = 0;
                if (parts.length > 1) {
                    try {
                        exitCode = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException ignored) {
                    }
                }
                System.exit(exitCode);
            } else if (command.equals("echo")) {
                String output = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length));
                System.out.println(output);
            } else if (command.equals("type")) {
                if (parts.length > 1) {
                    String target = parts[1];
                    if (BUILTINS.contains(target)) {
                        System.out.println(target + " is a shell builtin");
                    } else {
                        Path executable = findExecutable(target);
                        if (executable != null) {
                            System.out.println(target + " is " + executable);
                        } else {
                            System.out.println(target + ": not found");
                        }
                    }
                }
            } else if (command.equals("pwd")) {
                System.out.println(currentDir);
            } else if (command.equals("cd")) {
                if (parts.length > 1) {
                    String targetPath = parts[1];
                    Path target = currentDir.resolve(targetPath).normalize();
                    if (Files.isDirectory(target)) {
                        currentDir = target;
                    } else {
                        System.out.println("cd: " + targetPath + ": No such file or directory");
                    }
                }
            } else {
                Path executable = findExecutable(command);
                if (executable != null) {
                    ProcessBuilder pb = new ProcessBuilder(parts);
                    pb.directory(currentDir.toFile());
                    pb.inheritIO();
                    Process process = pb.start();
                    process.waitFor();
                } else {
                    System.out.println(input + ": command not found");
                }
            }
        }
    }

    private static Path findExecutable(String command) {
        if (command.contains("/") || command.contains(File.separator)) {
            try {
                Path path = currentDir.resolve(command);
                if (Files.isRegularFile(path) && Files.isExecutable(path)) {
                    return path;
                }
            } catch (Exception ignored) {
            }
            return null;
        }
        return findExecutableInPath(command);
    }

    private static Path findExecutableInPath(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) {
            return null;
        }
        String[] dirs = pathEnv.split(Pattern.quote(File.pathSeparator));
        for (String dir : dirs) {
            if (dir.isEmpty()) {
                continue;
            }
            try {
                Path path = Paths.get(dir, command);
                if (Files.isRegularFile(path) && Files.isExecutable(path)) {
                    return path;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
