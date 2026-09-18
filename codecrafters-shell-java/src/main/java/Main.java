import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
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
            List<String> parsedArgs = parseArguments(input);
            if (parsedArgs.isEmpty()) {
                continue;
            }
            String command = parsedArgs.get(0);

            if (command.equals("exit")) {
                int exitCode = 0;
                if (parsedArgs.size() > 1) {
                    try {
                        exitCode = Integer.parseInt(parsedArgs.get(1));
                    } catch (NumberFormatException ignored) {
                    }
                }
                System.exit(exitCode);
            } else if (command.equals("echo")) {
                String output = String.join(" ", parsedArgs.subList(1, parsedArgs.size()));
                System.out.println(output);
            } else if (command.equals("type")) {
                if (parsedArgs.size() > 1) {
                    String target = parsedArgs.get(1);
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
                String home = System.getenv("HOME");
                if (home == null || home.isEmpty()) {
                    home = System.getProperty("user.home");
                }
                String targetPath = parsedArgs.size() > 1 ? parsedArgs.get(1) : home;
                if (targetPath.equals("~")) {
                    targetPath = home;
                } else if (targetPath.startsWith("~/")) {
                    targetPath = home + targetPath.substring(1);
                }

                Path target = currentDir.resolve(targetPath).normalize();
                if (Files.isDirectory(target)) {
                    currentDir = target;
                } else {
                    System.out.println("cd: " + targetPath + ": No such file or directory");
                }
            } else {
                Path executable = findExecutable(command);
                if (executable != null) {
                    ProcessBuilder pb = new ProcessBuilder(parsedArgs);
                    pb.directory(currentDir.toFile());
                    pb.inheritIO();
                    Process process = pb.start();
                    process.waitFor();
                } else {
                    System.out.println(command + ": command not found");
                }
            }
        }
    }

    private static List<String> parseArguments(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean hasToken = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (inSingleQuote) {
                if (c == '\'') {
                    inSingleQuote = false;
                } else {
                    current.append(c);
                }
            } else if (inDoubleQuote) {
                if (c == '"') {
                    inDoubleQuote = false;
                } else {
                    current.append(c);
                }
            } else {
                if (c == '\\') {
                    if (i + 1 < input.length()) {
                        i++;
                        current.append(input.charAt(i));
                        hasToken = true;
                    } else {
                        current.append(c);
                        hasToken = true;
                    }
                } else if (c == '\'') {
                    inSingleQuote = true;
                    hasToken = true;
                } else if (c == '"') {
                    inDoubleQuote = true;
                    hasToken = true;
                } else if (Character.isWhitespace(c)) {
                    if (hasToken) {
                        args.add(current.toString());
                        current.setLength(0);
                        hasToken = false;
                    }
                } else {
                    current.append(c);
                    hasToken = true;
                }
            }
        }
        if (hasToken) {
            args.add(current.toString());
        }
        return args;
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
