import java.nio.charset.StandardCharsets;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class Main {
    private static final Set<String> BUILTINS = Set.of("echo", "exit", "type", "pwd", "cd", "complete", "jobs", "history");
    private static final Map<String, String> COMPLETION_SPECS = new HashMap<>();
    private static Path currentDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    private static final AtomicInteger nextJobId = new AtomicInteger(1);

    static class Job {
        int id;
        long pid;
        String command;
        Process process;

        Job(int id, long pid, String command, Process process) {
            this.id = id;
            this.pid = pid;
            this.command = command;
            this.process = process;
        }
    }

    private static final List<Job> backgroundJobs = new ArrayList<>();
    private static final List<String> commandHistory = new ArrayList<>();

    static class ParsedCommand {
        List<String> cmdArgs = new ArrayList<>();
        String redirectOutFile = null;
        String redirectErrFile = null;
        boolean appendOut = false;
        boolean appendErr = false;
    }

    public static void main(String[] args) throws Exception {
        enableRawMode();

        while (true) {
            reapCompletedJobs(System.out);
            System.out.print("$ ");
            System.out.flush();
            String input = readLineWithCompletion();
            if (input == null) {
                break;
            }
            commandHistory.add(input);
            List<String> parsedArgs = parseArguments(input);
            if (parsedArgs.isEmpty()) {
                continue;
            }

            // Check for background job operator (& at the end)
            boolean isBackground = false;
            if (!parsedArgs.isEmpty() && parsedArgs.get(parsedArgs.size() - 1).equals("&")) {
                isBackground = true;
                parsedArgs.remove(parsedArgs.size() - 1);
            }

            if (parsedArgs.isEmpty()) {
                continue;
            }

            // Split by pipeline operator "|"
            List<List<String>> pipeCommands = new ArrayList<>();
            List<String> currentCmd = new ArrayList<>();
            for (String token : parsedArgs) {
                if (token.equals("|")) {
                    if (!currentCmd.isEmpty()) {
                        pipeCommands.add(currentCmd);
                        currentCmd = new ArrayList<>();
                    }
                } else {
                    currentCmd.add(token);
                }
            }
            if (!currentCmd.isEmpty()) {
                pipeCommands.add(currentCmd);
            }

            if (pipeCommands.isEmpty()) {
                continue;
            }

            if (pipeCommands.size() > 1) {
                executePipeline(pipeCommands);
                continue;
            }

            ParsedCommand pc = parseRedirection(pipeCommands.get(0));
            List<String> cmdArgs = pc.cmdArgs;
            if (cmdArgs.isEmpty()) {
                continue;
            }

            String command = cmdArgs.get(0);
            Path outPath = pc.redirectOutFile != null ? resolvePath(pc.redirectOutFile) : null;
            Path errPath = pc.redirectErrFile != null ? resolvePath(pc.redirectErrFile) : null;
            boolean appendOut = pc.appendOut;
            boolean appendErr = pc.appendErr;

            if (BUILTINS.contains(command)) {
                PrintStream out = System.out;
                PrintStream err = System.err;
                boolean closeOut = false;
                boolean closeErr = false;
                if (outPath != null) {
                    out = new PrintStream(Files.newOutputStream(outPath,
                            StandardOpenOption.CREATE,
                            appendOut ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE));
                    closeOut = true;
                }
                if (errPath != null) {
                    err = new PrintStream(Files.newOutputStream(errPath,
                            StandardOpenOption.CREATE,
                            appendErr ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE));
                    closeErr = true;
                }

                try {
                    executeBuiltin(pc, System.in, out, err, false);
                } finally {
                    if (closeOut) {
                        out.close();
                    }
                    if (closeErr) {
                        err.close();
                    }
                }
            } else {
                Path executable = findExecutable(command);
                if (executable != null) {
                    if (isBackground) {
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
                        Process process = pb.start();
                        int maxJobId = 0;
                        for (Job j : backgroundJobs) {
                            if (j.id > maxJobId) {
                                maxJobId = j.id;
                            }
                        }
                        int jobId = maxJobId + 1;
                        long pid = process.pid();
                        backgroundJobs.add(new Job(jobId, pid, String.join(" ", cmdArgs), process));
                        System.out.println("[" + jobId + "] " + pid);
                        System.out.flush();
                    } else {
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
                    }
                } else {
                    System.out.println(command + ": command not found");
                }
            }
        }
    }

    private static void executePipeline(List<List<String>> pipeCommands) {
        List<ParsedCommand> parsedList = new ArrayList<>();
        for (List<String> rawTokens : pipeCommands) {
            try {
                ParsedCommand pc = parseRedirection(rawTokens);
                if (pc.cmdArgs.isEmpty()) {
                    return;
                }
                parsedList.add(pc);
            } catch (Exception e) {
                return;
            }
        }

        // Validate all commands exist
        for (ParsedCommand pc : parsedList) {
            String cmd = pc.cmdArgs.get(0);
            if (findExecutable(cmd) == null && !BUILTINS.contains(cmd)) {
                System.out.println(cmd + ": command not found");
                return;
            }
        }

        boolean hasBuiltin = false;
        for (ParsedCommand pc : parsedList) {
            if (BUILTINS.contains(pc.cmdArgs.get(0))) {
                hasBuiltin = true;
                break;
            }
        }

        if (!hasBuiltin) {
            executeExternalPipeline(parsedList);
            return;
        }

        executeMixedPipeline(parsedList);
    }

    private static void executeExternalPipeline(List<ParsedCommand> parsedList) {
        List<ProcessBuilder> builders = new ArrayList<>();
        for (int i = 0; i < parsedList.size(); i++) {
            ParsedCommand pc = parsedList.get(i);
            ProcessBuilder pb = new ProcessBuilder(pc.cmdArgs);
            pb.directory(currentDir.toFile());

            // Stdin redirection
            if (i == 0) {
                pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            }

            // Stdout redirection
            if (i == parsedList.size() - 1) {
                if (pc.redirectOutFile != null) {
                    Path outPath = resolvePath(pc.redirectOutFile);
                    pb.redirectOutput(pc.appendOut ? ProcessBuilder.Redirect.appendTo(outPath.toFile()) : ProcessBuilder.Redirect.to(outPath.toFile()));
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }
            }

            // Stderr redirection
            if (pc.redirectErrFile != null) {
                Path errPath = resolvePath(pc.redirectErrFile);
                pb.redirectError(pc.appendErr ? ProcessBuilder.Redirect.appendTo(errPath.toFile()) : ProcessBuilder.Redirect.to(errPath.toFile()));
            } else {
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            }

            builders.add(pb);
        }

        setRawMode(false);
        try {
            List<Process> processes = ProcessBuilder.startPipeline(builders);
            Process last = processes.get(processes.size() - 1);
            last.waitFor();
            for (Process p : processes) {
                if (p.isAlive()) {
                    p.destroy();
                }
                p.waitFor();
            }
        } catch (Exception ignored) {
        } finally {
            setRawMode(true);
        }
    }

    private static void executeMixedPipeline(List<ParsedCommand> parsedList) {
        int n = parsedList.size();
        boolean[] isBuiltin = new boolean[n];
        for (int i = 0; i < n; i++) {
            isBuiltin[i] = BUILTINS.contains(parsedList.get(i).cmdArgs.get(0));
        }

        Process[] processes = new Process[n];
        Thread[] threads = new Thread[n];
        List<Thread> pumpThreads = new ArrayList<>();
        PipedInputStream[] builtinPipeIn = new PipedInputStream[n];
        PipedOutputStream[] builtinPipeOut = new PipedOutputStream[n];

        setRawMode(false);
        try {
            // 1. Start all external processes
            for (int i = 0; i < n; i++) {
                if (!isBuiltin[i]) {
                    ParsedCommand pc = parsedList.get(i);
                    ProcessBuilder pb = new ProcessBuilder(pc.cmdArgs);
                    pb.directory(currentDir.toFile());

                    if (i == 0) {
                        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                    }

                    if (i == n - 1) {
                        if (pc.redirectOutFile != null) {
                            Path outPath = resolvePath(pc.redirectOutFile);
                            pb.redirectOutput(pc.appendOut ? ProcessBuilder.Redirect.appendTo(outPath.toFile()) : ProcessBuilder.Redirect.to(outPath.toFile()));
                        } else {
                            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                        }
                    } else {
                        if (pc.redirectOutFile != null) {
                            Path outPath = resolvePath(pc.redirectOutFile);
                            pb.redirectOutput(pc.appendOut ? ProcessBuilder.Redirect.appendTo(outPath.toFile()) : ProcessBuilder.Redirect.to(outPath.toFile()));
                        }
                    }

                    if (pc.redirectErrFile != null) {
                        Path errPath = resolvePath(pc.redirectErrFile);
                        pb.redirectError(pc.appendErr ? ProcessBuilder.Redirect.appendTo(errPath.toFile()) : ProcessBuilder.Redirect.to(errPath.toFile()));
                    } else {
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }

                    processes[i] = pb.start();
                }
            }

            // 2. Set up inter-stage connections
            for (int i = 0; i < n - 1; i++) {
                if (!isBuiltin[i] && !isBuiltin[i + 1]) {
                    if (parsedList.get(i).redirectOutFile == null) {
                        Process pSrc = processes[i];
                        Process pDst = processes[i + 1];
                        Thread pump = new Thread(() -> {
                            try (InputStream in = pSrc.getInputStream();
                                 OutputStream out = pDst.getOutputStream()) {
                                in.transferTo(out);
                            } catch (IOException ignored) {
                            }
                        });
                        pump.start();
                        pumpThreads.add(pump);
                    } else {
                        try {
                            processes[i + 1].getOutputStream().close();
                        } catch (IOException ignored) {}
                    }
                } else if (isBuiltin[i] && isBuiltin[i + 1]) {
                    if (parsedList.get(i).redirectOutFile == null) {
                        PipedOutputStream pos = new PipedOutputStream();
                        PipedInputStream pis = new PipedInputStream(pos, 65536);
                        builtinPipeOut[i] = pos;
                        builtinPipeIn[i + 1] = pis;
                    }
                } else if (isBuiltin[i] && !isBuiltin[i + 1]) {
                    if (parsedList.get(i).redirectOutFile != null) {
                        try {
                            processes[i + 1].getOutputStream().close();
                        } catch (IOException ignored) {}
                    }
                }
            }

            // 3. Start Builtin threads
            for (int i = 0; i < n; i++) {
                if (isBuiltin[i]) {
                    ParsedCommand pc = parsedList.get(i);
                    InputStream inStream;
                    if (i == 0) {
                        inStream = System.in;
                    } else if (!isBuiltin[i - 1]) {
                        if (parsedList.get(i - 1).redirectOutFile != null) {
                            inStream = InputStream.nullInputStream();
                        } else {
                            inStream = processes[i - 1].getInputStream();
                        }
                    } else {
                        inStream = builtinPipeIn[i] != null ? builtinPipeIn[i] : InputStream.nullInputStream();
                    }

                    PrintStream outStream;
                    if (pc.redirectOutFile != null) {
                        Path outPath = resolvePath(pc.redirectOutFile);
                        outStream = new PrintStream(Files.newOutputStream(outPath,
                                StandardOpenOption.CREATE,
                                pc.appendOut ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE));
                    } else if (i == n - 1) {
                        outStream = System.out;
                    } else if (!isBuiltin[i + 1]) {
                        outStream = new PrintStream(processes[i + 1].getOutputStream(), true);
                    } else {
                        outStream = builtinPipeOut[i] != null ? new PrintStream(builtinPipeOut[i], true) : new PrintStream(OutputStream.nullOutputStream());
                    }

                    PrintStream errStream;
                    if (pc.redirectErrFile != null) {
                        Path errPath = resolvePath(pc.redirectErrFile);
                        errStream = new PrintStream(Files.newOutputStream(errPath,
                                StandardOpenOption.CREATE,
                                pc.appendErr ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE));
                    } else {
                        errStream = System.err;
                    }

                    final InputStream finalIn = inStream;
                    final PrintStream finalOut = outStream;
                    final PrintStream finalErr = errStream;

                    Thread t = new Thread(() -> {
                        try {
                            executeBuiltin(pc, finalIn, finalOut, finalErr, true);
                        } finally {
                            if (finalOut != null && finalOut != System.out) {
                                finalOut.flush();
                                finalOut.close();
                            }
                            if (finalIn != null && finalIn != System.in) {
                                try {
                                    finalIn.close();
                                } catch (IOException ignored) {}
                            }
                            if (finalErr != null && finalErr != System.err) {
                                finalErr.flush();
                                finalErr.close();
                            }
                        }
                    });
                    threads[i] = t;
                    t.start();
                }
            }

            // 4. Wait for completion: wait on the last stage first
            if (isBuiltin[n - 1]) {
                if (threads[n - 1] != null) {
                    threads[n - 1].join();
                }
            } else {
                if (processes[n - 1] != null) {
                    processes[n - 1].waitFor();
                }
            }

            // 5. Unblock and clean up any remaining stages
            for (int i = 0; i < n; i++) {
                if (!isBuiltin[i] && processes[i] != null) {
                    if (processes[i].isAlive()) {
                        processes[i].destroy();
                    }
                    processes[i].waitFor();
                }
            }
            for (int i = 0; i < n; i++) {
                if (isBuiltin[i] && threads[i] != null) {
                    threads[i].join();
                }
            }
            for (Thread pt : pumpThreads) {
                pt.join();
            }
        } catch (Exception ignored) {
            for (Process p : processes) {
                if (p != null && p.isAlive()) {
                    p.destroy();
                }
            }
        } finally {
            setRawMode(true);
        }
    }

    private static int executeBuiltin(ParsedCommand pc, InputStream in, PrintStream out, PrintStream err, boolean inSubshell) {
        List<String> cmdArgs = pc.cmdArgs;
        if (cmdArgs.isEmpty()) {
            return 0;
        }
        String command = cmdArgs.get(0);
        if (command.equals("exit")) {
            int exitCode = 0;
            if (cmdArgs.size() > 1) {
                try {
                    exitCode = Integer.parseInt(cmdArgs.get(1));
                } catch (NumberFormatException ignored) {
                }
            }
            if (!inSubshell) {
                if (out != System.out) {
                    out.close();
                }
                if (err != System.err) {
                    err.close();
                }
                setRawMode(false);
                System.exit(exitCode);
            }
            return exitCode;
        } else if (command.equals("echo")) {
            String output = String.join(" ", cmdArgs.subList(1, cmdArgs.size()));
            out.println(output);
            out.flush();
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
            out.flush();
        } else if (command.equals("pwd")) {
            out.println(currentDir);
            out.flush();
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
                if (!inSubshell) {
                    currentDir = target;
                }
            } else {
                err.println("cd: " + targetPath + ": No such file or directory");
                err.flush();
                return 1;
            }
        } else if (command.equals("complete")) {
            if (cmdArgs.size() >= 3 && cmdArgs.get(1).equals("-p")) {
                String target = cmdArgs.get(2);
                if (COMPLETION_SPECS.containsKey(target)) {
                    out.println("complete -C '" + COMPLETION_SPECS.get(target) + "' " + target);
                } else {
                    err.println("complete: " + target + ": no completion specification");
                }
            } else if (!inSubshell) {
                if (cmdArgs.size() >= 3 && cmdArgs.get(1).equals("-r")) {
                    String target = cmdArgs.get(2);
                    COMPLETION_SPECS.remove(target);
                } else if (cmdArgs.size() >= 4 && cmdArgs.get(1).equals("-C")) {
                    String scriptPath = cmdArgs.get(2);
                    String target = cmdArgs.get(3);
                    COMPLETION_SPECS.put(target, scriptPath);
                }
            }
            out.flush();
            err.flush();
        } else if (command.equals("history")) {
            synchronized (commandHistory) {
                if (cmdArgs.size() >= 3 && cmdArgs.get(1).equals("-r")) {
                    String filePath = cmdArgs.get(2);
                    try {
                        Path p = currentDir.resolve(filePath).normalize();
                        if (Files.exists(p)) {
                            List<String> fileLines = Files.readAllLines(p);
                            for (String fl : fileLines) {
                                if (!fl.isEmpty()) {
                                    commandHistory.add(fl);
                                }
                            }
                        }
                    } catch (IOException ignored) {}
                } else {
                    int limit = commandHistory.size();
                    if (cmdArgs.size() > 1) {
                        try {
                            int n = Integer.parseInt(cmdArgs.get(1));
                            if (n >= 0) {
                                limit = n;
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                    int startIndex = Math.max(0, commandHistory.size() - limit);
                    for (int j = startIndex; j < commandHistory.size(); j++) {
                        out.printf("%5d  %s\n", j + 1, commandHistory.get(j));
                    }
                }
            }
            out.flush();
        } else if (command.equals("jobs")) {
            synchronized (backgroundJobs) {
                int n = backgroundJobs.size();
                List<Job> remainingJobs = new ArrayList<>();
                for (int j = 0; j < n; j++) {
                    Job job = backgroundJobs.get(j);
                    String marker = " ";
                    if (j == n - 1) {
                        marker = "+";
                    } else if (j == n - 2) {
                        marker = "-";
                    }
                    boolean isAlive = job.process != null && job.process.isAlive();
                    String status = isAlive ? "Running" : "Done";
                    String trailing = isAlive ? " &" : "";
                    out.printf("[%d]%s  %-24s%s%s\n", job.id, marker, status, job.command, trailing);
                    if (isAlive) {
                        remainingJobs.add(job);
                    }
                }
                if (!inSubshell) {
                    backgroundJobs.clear();
                    backgroundJobs.addAll(remainingJobs);
                }
            }
            out.flush();
        }
        return 0;
    }

    private static ParsedCommand parseRedirection(List<String> tokens) throws Exception {
        ParsedCommand pc = new ParsedCommand();
        for (int i = 0; i < tokens.size(); i++) {
            String arg = tokens.get(i);
            if ((arg.equals(">>") || arg.equals("1>>")) && i + 1 < tokens.size()) {
                pc.appendOut = true;
                pc.redirectOutFile = tokens.get(i + 1);
                prepareRedirectionFile(pc.redirectOutFile, true);
                i++;
            } else if (arg.equals("2>>") && i + 1 < tokens.size()) {
                pc.appendErr = true;
                pc.redirectErrFile = tokens.get(i + 1);
                prepareRedirectionFile(pc.redirectErrFile, true);
                i++;
            } else if (arg.startsWith(">>") && arg.length() > 2) {
                pc.appendOut = true;
                pc.redirectOutFile = arg.substring(2);
                prepareRedirectionFile(pc.redirectOutFile, true);
            } else if (arg.startsWith("1>>") && arg.length() > 3) {
                pc.appendOut = true;
                pc.redirectOutFile = arg.substring(3);
                prepareRedirectionFile(pc.redirectOutFile, true);
            } else if (arg.startsWith("2>>") && arg.length() > 3) {
                pc.appendErr = true;
                pc.redirectErrFile = arg.substring(3);
                prepareRedirectionFile(pc.redirectErrFile, true);
            } else if ((arg.equals(">") || arg.equals("1>")) && i + 1 < tokens.size()) {
                pc.appendOut = false;
                pc.redirectOutFile = tokens.get(i + 1);
                prepareRedirectionFile(pc.redirectOutFile, false);
                i++;
            } else if (arg.equals("2>") && i + 1 < tokens.size()) {
                pc.appendErr = false;
                pc.redirectErrFile = tokens.get(i + 1);
                prepareRedirectionFile(pc.redirectErrFile, false);
                i++;
            } else if (arg.startsWith(">") && !arg.startsWith(">>") && arg.length() > 1) {
                pc.appendOut = false;
                pc.redirectOutFile = arg.substring(1);
                prepareRedirectionFile(pc.redirectOutFile, false);
            } else if (arg.startsWith("1>") && !arg.startsWith("1>>") && arg.length() > 2) {
                pc.appendOut = false;
                pc.redirectOutFile = arg.substring(2);
                prepareRedirectionFile(pc.redirectOutFile, false);
            } else if (arg.startsWith("2>") && !arg.startsWith("2>>") && arg.length() > 2) {
                pc.appendErr = false;
                pc.redirectErrFile = arg.substring(2);
                prepareRedirectionFile(pc.redirectErrFile, false);
            } else {
                pc.cmdArgs.add(arg);
            }
        }
        return pc;
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
        int historyIndex = commandHistory.size();
        String savedCurrentLine = "";

        while (true) {
            int ch = System.in.read();
            if (ch == 27) { // ESC sequence (e.g. arrow keys)
                int next1 = System.in.read();
                if (next1 == '[') {
                    int next2 = System.in.read();
                    if (next2 == 'A') { // UP arrow
                        if (historyIndex > 0) {
                            if (historyIndex == commandHistory.size()) {
                                savedCurrentLine = line.toString();
                            }
                            historyIndex--;
                            String target = commandHistory.get(historyIndex);
                            // Clear current line on screen
                            while (line.length() > 0) {
                                System.out.print("\b \b");
                                line.deleteCharAt(line.length() - 1);
                            }
                            line.append(target);
                            System.out.print(target);
                            System.out.flush();
                        }
                        continue;
                    } else if (next2 == 'B') { // DOWN arrow
                        if (historyIndex < commandHistory.size()) {
                            historyIndex++;
                            String target = (historyIndex == commandHistory.size()) ? savedCurrentLine : commandHistory.get(historyIndex);
                            while (line.length() > 0) {
                                System.out.print("\b \b");
                                line.deleteCharAt(line.length() - 1);
                            }
                            line.append(target);
                            System.out.print(target);
                            System.out.flush();
                        }
                        continue;
                    }
                }
                continue;
            }
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
                String word = "";
                Set<String> matches = null;

                if (lastSpace == -1) {
                    word = current;
                    matches = getCommandCompletions(word);
                } else {
                    word = current.substring(lastSpace + 1);
                    String firstWord = current.trim().split("\\s+")[0];
                    if (COMPLETION_SPECS.containsKey(firstWord)) {
                        String script = COMPLETION_SPECS.get(firstWord);
                        String prevWord = "";
                        String beforeCurrentWord = current.substring(0, lastSpace).trim();
                        if (!beforeCurrentWord.isEmpty()) {
                            int prevSpace = beforeCurrentWord.lastIndexOf(' ');
                            if (prevSpace == -1) {
                                prevWord = beforeCurrentWord;
                            } else {
                                prevWord = beforeCurrentWord.substring(prevSpace + 1);
                            }
                        }
                        List<String> scriptOutput = runCompleterScript(script, firstWord, word, prevWord, current);
                        matches = new TreeSet<>(scriptOutput);
                    } else {
                        matches = getFileCompletions(word);
                    }
                }

                if (matches.size() == 1) {
                    String match = matches.iterator().next();
                    String completion = match.startsWith(word) ? match.substring(word.length()) : match;
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

    private static List<String> runCompleterScript(String scriptPath, String command, String word, String prevWord, String compLine) {
        List<String> results = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(scriptPath, command, word, prevWord);
            pb.environment().put("COMP_LINE", compLine);
            pb.environment().put("COMP_POINT", String.valueOf(compLine.getBytes(StandardCharsets.UTF_8).length));
            pb.directory(currentDir.toFile());
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        results.add(line);
                    }
                }
            }
            p.waitFor();
        } catch (Exception ignored) {}
        return results;
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
                } else if (c == '|') {
                    if (hasToken) {
                        args.add(current.toString());
                        current.setLength(0);
                        hasToken = false;
                    }
                    if (i + 1 < input.length() && input.charAt(i + 1) == '|') {
                        i++;
                        args.add("||");
                    } else {
                        args.add("|");
                    }
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

    private static void reapCompletedJobs(PrintStream out) {
        int n = backgroundJobs.size();
        List<Job> remainingJobs = new ArrayList<>();
        for (int j = 0; j < n; j++) {
            Job job = backgroundJobs.get(j);
            String marker = " ";
            if (j == n - 1) {
                marker = "+";
            } else if (j == n - 2) {
                marker = "-";
            }
            boolean isAlive = job.process != null && job.process.isAlive();
            if (!isAlive) {
                out.printf("[%d]%s  %-24s%s%n", job.id, marker, "Done", job.command);
            } else {
                remainingJobs.add(job);
            }
        }
        backgroundJobs.clear();
        backgroundJobs.addAll(remainingJobs);
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
