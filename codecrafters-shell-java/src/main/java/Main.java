import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

public class Main {
    private static final Set<String> BUILTINS = Set.of("echo", "exit", "type", "pwd", "cd", "complete");
    private static Path currentDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();

    public static void main(String[] args) throws Exception {
        enableRawMode();

        while (true) {
            System.out.print("$ ");
            System.out.flush();
            String input = readLineWithCompletion();
            if (input == null) {
                break;
            }
            List<String> parsedArgs = parseArguments(input);
            if (parsedArgs.isEmpty()) {
                continue;
            }

            // Scan for redirection operators (>>, 1>>, >, 1>, 2>>, 2>)
            List<String> cmdArgs = new ArrayList<>();
            String redirectOutFile = null;
            String redirectErrFile = null;
            boolean appendOut = false;
            boolean appendErr = false;

            for (int i = 0; i < parsedArgs.size(); i++) {
                String arg = parsedArgs.get(i);
                if ((arg.equals(">>") || arg.equals("1>>")) && i + 1 < parsedArgs.size()) {
                    appendOut = true;
                    redirectOutFile = parsedArgs.get(i + 1);
                    prepareRedirectionFile(redirectOutFile, true);
                    i++;
                } else if (arg.equals("2>>") && i + 1 < parsedArgs.size()) {
                    appendErr = true;
                    redirectErrFile = parsedArgs.get(i + 1);
                    prepareRedirectionFile(redirectErrFile, true);
                    i++;
                } else if (arg.startsWith(">>") && arg.length() > 2) {
                    appendOut = true;
                    redirectOutFile = arg.substring(2);
                    prepareRedirectionFile(redirectOutFile, true);
                } else if (arg.startsWith("1>>") && arg.length() > 3) {
                    appendOut = true;
                    redirectOutFile = arg.substring(3);
                    prepareRedirectionFile(redirectOutFile, true);
                } else if (arg.startsWith("2>>") && arg.length() > 3) {
                    appendErr = true;
                    redirectErrFile = arg.substring(3);
                    prepareRedirectionFile(redirectErrFile, true);
                } else if ((arg.equals(">") || arg.equals("1>")) && i + 1 < parsedArgs.size()) {
                    appendOut = false;
                    redirectOutFile = parsedArgs.get(i + 1);
                    prepareRedirectionFile(redirectOutFile, false);
                    i++;
                } else if (arg.equals("2>") && i + 1 < parsedArgs.size()) {
                    appendErr = false;
                    redirectErrFile = parsedArgs.get(i + 1);
                    prepareRedirectionFile(redirectErrFile, false);
                    i++;
                } else if (arg.startsWith(">") && !arg.startsWith(">>") && arg.length() > 1) {
                    appendOut = false;
                    redirectOutFile = arg.substring(1);
                    prepareRedirectionFile(redirectOutFile, false);
                } else if (arg.startsWith("1>") && !arg.startsWith("1>>") && arg.length() > 2) {
                    appendOut = false;
                    redirectOutFile = arg.substring(2);
                    prepareRedirectionFile(redirectOutFile, false);
                } else if (arg.startsWith("2>") && !arg.startsWith("2>>") && arg.length() > 2) {
                    appendErr = false;
                    redirectErrFile = arg.substring(2);
                    prepareRedirectionFile(redirectErrFile, false);
                } else {
                    cmdArgs.add(arg);
                }
            }

            if (cmdArgs.isEmpty()) {
                continue;
            }

            String command = cmdArgs.get(0);
            Path outPath = redirectOutFile != null ? resolvePath(redirectOutFile) : null;
            Path errPath = redirectErrFile != null ? resolvePath(redirectErrFile) : null;

            if (BUILTINS.contains(command)) {
                PrintStream out = System.out;
                boolean closeOut = false;
                if (outPath != null) {
                    out = new PrintStream(Files.newOutputStream(outPath,
                            StandardOpenOption.CREATE,
                            appendOut ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE));
                    closeOut = true;
                }

                try {
                    if (command.equals("exit")) {
                        int exitCode = 0;
                        if (cmdArgs.size() > 1) {
                            try {
                                exitCode = Integer.parseInt(cmdArgs.get(1));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        if (closeOut) {
                            out.close();
                            closeOut = false;
                        }
                        setRawMode(false);
                        System.exit(exitCode);
                    } else if (command.equals("echo")) {
                        String output = String.join(" ", cmdArgs.subList(1, cmdArgs.size()));
                        out.println(output);
                    } else if (command.equals("type")) {
                        if (cmdArgs.size() > 1) {
                            String target = cmdArgs.get(1);
                            if (BUILTINS.contains(target)) {
                                out.println(target + " is a shell builtin");
                            } else {
                                Path executable = findExecutable(target);
                                if (executable != null) {
                                    out.println(target + " is " + executable);
                                } else {
                                    out.println(target + ": not found");
                                }
                            }
                        }
                    } else if (command.equals("pwd")) {
                        out.println(currentDir);
                    } else if (command.equals("cd")) {
                        String home = System.getenv("HOME");
                        if (home == null || home.isEmpty()) {
                            home = System.getProperty("user.home");
                        }
                        String targetPath = cmdArgs.size() > 1 ? cmdArgs.get(1) : home;
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
                    } else if (command.equals("complete")) {
                        // complete builtin implementation (stubbed for now)
                    }
                } finally {
                    if (closeOut) {
                        out.close();
                    }
                }
            } else {
                Path executable = findExecutable(command);
                if (executable != null) {
                    setRawMode(false);
                    try {
                        ProcessBuilder pb = new ProcessBuilder(cmdArgs);
                        pb.directory(currentDir.toFile());
                        if (outPath != null) {
                            pb.redirectOutput(appendOut ? ProcessBuilder.Redirect.appendTo(outPath.toFile()) : ProcessBuilder.Redirect.to(outPath.toFile()));
                        } else {
                            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                        }
                        if (errPath != null) {
                            pb.redirectError(appendErr ? ProcessBuilder.Redirect.appendTo(errPath.toFile()) : ProcessBuilder.Redirect.to(errPath.toFile()));
                        } else {
                            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                        }
                        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                        Process process = pb.start();
                        process.waitFor();
                    } finally {
                        setRawMode(true);
                    }
                } else {
                    System.out.println(command + ": command not found");
                }
            }
        }
    }

    private static void setRawMode(boolean raw) {
        try {
            String cmd = raw ? "stty -icanon -echo </dev/tty" : "stty sane </dev/tty";
            new ProcessBuilder("/bin/sh", "-c", cmd).inheritIO().start().waitFor();
        } catch (Exception ignored) {}
    }

    private static void enableRawMode() {
        setRawMode(true);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            setRawMode(false);
        }));
    }

    private static String readLineWithCompletion() throws IOException {
        StringBuilder line = new StringBuilder();
        int consecutiveTabs = 0;
        while (true) {
            int ch = System.in.read();
            if (ch == -1) {
                return line.length() > 0 ? line.toString() : null;
            }
            if (ch == '\n' || ch == '\r') {
                consecutiveTabs = 0;
                System.out.print("\n");
                System.out.flush();
                return line.toString();
            } else if (ch == '\t') {
                String current = line.toString();
                int lastSpace = current.lastIndexOf(' ');
                String word;
                Set<String> matches;
                if (lastSpace == -1) {
                    word = current;
                    matches = getCommandCompletions(word);
                } else {
                    word = current.substring(lastSpace + 1);
                    matches = getFileCompletions(word);
                }

                if (matches.size() == 1) {
                    String match = matches.iterator().next();
                    String completion = match.substring(word.length());
                    if (!match.endsWith("/")) {
                        completion += " ";
                    }
                    line.append(completion);
                    System.out.print(completion);
                    System.out.flush();
                    consecutiveTabs = 0;
                } else if (matches.size() > 1) {
                    String lcp = longestCommonPrefix(matches);
                    if (lcp.length() > word.length()) {
                        String completion = lcp.substring(word.length());
                        line.append(completion);
                        System.out.print(completion);
                        System.out.flush();
                        consecutiveTabs = 0;
                    } else {
                        consecutiveTabs++;
                        if (consecutiveTabs == 1) {
                            System.out.print("\u0007");
                            System.out.flush();
                        } else if (consecutiveTabs >= 2) {
                            System.out.print("\n");
                            System.out.print(String.join("  ", matches));
                            System.out.print("\n");
                            System.out.print("$ " + current);
                            System.out.flush();
                            consecutiveTabs = 0;
                        }
                    }
                } else {
                    System.out.print("\u0007");
                    System.out.flush();
                    consecutiveTabs = 0;
                }
            } else if (ch == 127 || ch == '\b') {
                consecutiveTabs = 0;
                if (line.length() > 0) {
                    line.deleteCharAt(line.length() - 1);
                    System.out.print("\b \b");
                    System.out.flush();
                }
            } else if (ch >= 32) {
                consecutiveTabs = 0;
                line.append((char) ch);
                System.out.print((char) ch);
                System.out.flush();
            } else {
                consecutiveTabs = 0;
            }
        }
    }

    private static Set<String> getFileCompletions(String prefix) {
        Set<String> candidates = new TreeSet<>();
        Path targetDir;
        String dirPart = "";
        String namePrefix = prefix;
        int lastSlash = prefix.lastIndexOf('/');
        if (lastSlash != -1) {
            dirPart = prefix.substring(0, lastSlash + 1);
            namePrefix = prefix.substring(lastSlash + 1);
            String searchPath = dirPart;
            if (searchPath.equals("~") || searchPath.startsWith("~/")) {
                String home = System.getenv("HOME");
                if (home == null || home.isEmpty()) {
                    home = System.getProperty("user.home");
                }
                searchPath = home + searchPath.substring(1);
            }
            if (searchPath.startsWith("/")) {
                targetDir = Paths.get(searchPath).normalize();
            } else {
                targetDir = currentDir.resolve(searchPath).normalize();
            }
        } else {
            targetDir = currentDir;
        }

        File[] files = targetDir.toFile().listFiles();
        if (files != null) {
            for (File file : files) {
                try {
                    String name = file.getName();
                    if (!namePrefix.startsWith(".") && name.startsWith(".")) {
                        continue;
                    }
                    if (name.startsWith(namePrefix)) {
                        if (file.isDirectory()) {
                            candidates.add(dirPart + name + "/");
                        } else {
                            candidates.add(dirPart + name);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return candidates;
    }

    private static Set<String> getCommandCompletions(String prefix) {
        Set<String> candidates = new TreeSet<>();
        for (String builtin : List.of("echo", "exit")) {
            if (builtin.startsWith(prefix)) {
                candidates.add(builtin);
            }
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null && !pathEnv.isEmpty()) {
            String[] dirs = pathEnv.split(Pattern.quote(File.pathSeparator));
            for (String dirStr : dirs) {
                if (dirStr.isEmpty()) {
                    continue;
                }
                File dir = new File(dirStr);
                if (!dir.exists() || !dir.isDirectory() || !dir.canRead()) {
                    continue;
                }
                File[] files = dir.listFiles();
                if (files == null) {
                    continue;
                }
                for (File file : files) {
                    try {
                        if (file.isFile() && file.canExecute()) {
                            String name = file.getName();
                            if (name.startsWith(prefix)) {
                                candidates.add(name);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        return candidates;
    }

    private static String longestCommonPrefix(Set<String> strings) {
        if (strings == null || strings.isEmpty()) {
            return "";
        }
        String prefix = null;
        for (String s : strings) {
            if (prefix == null) {
                prefix = s;
            } else {
                int i = 0;
                while (i < prefix.length() && i < s.length() && prefix.charAt(i) == s.charAt(i)) {
                    i++;
                }
                prefix = prefix.substring(0, i);
                if (prefix.isEmpty()) {
                    break;
                }
            }
        }
        return prefix != null ? prefix : "";
    }

    private static void prepareRedirectionFile(String pathStr, boolean append) throws Exception {
        Path p = resolvePath(pathStr);
        if (p.getParent() != null) {
            Files.createDirectories(p.getParent());
        }
        if (append) {
            if (!Files.exists(p)) {
                Files.createFile(p);
            }
        } else {
            Files.write(p, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        }
    }

    private static Path resolvePath(String pathStr) {
        String home = System.getenv("HOME");
        if (home == null || home.isEmpty()) {
            home = System.getProperty("user.home");
        }
        if (pathStr.equals("~")) {
            pathStr = home;
        } else if (pathStr.startsWith("~/")) {
            pathStr = home + pathStr.substring(1);
        }
        return currentDir.resolve(pathStr).normalize();
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
                if (c == '\\') {
                    if (i + 1 < input.length()) {
                        char next = input.charAt(i + 1);
                        if (next == '\"' || next == '\\' || next == '$' || next == '`') {
                            i++;
                            current.append(next);
                        } else {
                            current.append(c);
                        }
                    } else {
                        current.append(c);
                    }
                } else if (c == '\"') {
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
                } else if (c == '\"') {
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
            } catch (Exception ignored) {}
        }
        return null;
    }
}
