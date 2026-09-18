import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

public class Main {
    enum ColorMode {
        NEVER,
        ALWAYS,
        AUTO
    }

    static class FileInput {
        final String name;
        final Scanner scanner;
        FileInput(String name, Scanner scanner) {
            this.name = name;
            this.scanner = scanner;
        }
    }

    static class FileInputPath {
        final String displayPath;
        final File file;
        FileInputPath(String displayPath, File file) {
            this.displayPath = displayPath;
            this.file = file;
        }
    }

    public static void main(String[] args) {
        boolean onlyMatching = false;
        boolean isRecursive = false;
        ColorMode colorMode = ColorMode.NEVER;
        String pattern = null;
        List<String> filePaths = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-r") || args[i].equals("--recursive") || args[i].equals("-R")) {
                isRecursive = true;
            } else if (args[i].equals("-o") || args[i].equals("--only-matching")) {
                onlyMatching = true;
            } else if (args[i].equals("--color=always")) {
                colorMode = ColorMode.ALWAYS;
            } else if (args[i].equals("--color=never")) {
                colorMode = ColorMode.NEVER;
            } else if (args[i].equals("--color=auto")) {
                colorMode = ColorMode.AUTO;
            } else if (args[i].startsWith("--color=")) {
                String val = args[i].substring("--color=".length());
                if ("always".equalsIgnoreCase(val)) {
                    colorMode = ColorMode.ALWAYS;
                } else if ("auto".equalsIgnoreCase(val)) {
                    colorMode = ColorMode.AUTO;
                } else {
                    colorMode = ColorMode.NEVER;
                }
            } else if (args[i].equals("--color")) {
                if (i + 1 < args.length && (args[i + 1].equals("always") || args[i + 1].equals("never") || args[i + 1].equals("auto"))) {
                    String next = args[++i];
                    if ("always".equalsIgnoreCase(next)) {
                        colorMode = ColorMode.ALWAYS;
                    } else if ("auto".equalsIgnoreCase(next)) {
                        colorMode = ColorMode.AUTO;
                    } else {
                        colorMode = ColorMode.NEVER;
                    }
                } else {
                    colorMode = ColorMode.ALWAYS;
                }
            } else if (args[i].equals("-E")) {
                if (i + 1 < args.length) {
                    pattern = args[++i];
                }
            } else if (!args[i].startsWith("-")) {
                filePaths.add(args[i]);
            }
        }

        if (pattern == null) {
            System.out.println("Usage: ./your_program.sh [-r] [--color=always|auto|never] [-o] -E <pattern> [files...]");
            System.exit(1);
        }

        boolean shouldColor = (colorMode == ColorMode.ALWAYS) || (colorMode == ColorMode.AUTO && isStdoutTty());

        List<FileInput> inputs = new ArrayList<>();
        if (filePaths.isEmpty()) {
            inputs.add(new FileInput(null, new Scanner(System.in)));
        } else {
            for (String path : filePaths) {
                File f = new File(path);
                if (isRecursive && f.isDirectory()) {
                    List<FileInputPath> collected = new ArrayList<>();
                    collectFiles(f, path, collected);
                    for (FileInputPath fip : collected) {
                        try {
                            inputs.add(new FileInput(fip.displayPath, new Scanner(fip.file)));
                        } catch (IOException e) {
                            System.err.println("grep: " + fip.displayPath + ": " + e.getMessage());
                        }
                    }
                } else if (f.isFile()) {
                    try {
                        inputs.add(new FileInput(path, new Scanner(f)));
                    } catch (IOException e) {
                        System.err.println("grep: " + path + ": " + e.getMessage());
                    }
                } else if (f.isDirectory()) {
                    System.err.println("grep: " + path + ": Is a directory");
                } else {
                    System.err.println("grep: " + path + ": No such file or directory");
                }
            }
        }

        boolean printFilenamePrefix = isRecursive || filePaths.size() > 1;
        boolean matchedAny = false;

        for (FileInput input : inputs) {
            String prefix = (printFilenamePrefix && input.name != null) ? input.name + ":" : "";
            Scanner scanner = input.scanner;
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                if (onlyMatching) {
                    List<String> matches = findAllMatches(line, pattern);
                    for (String match : matches) {
                        System.out.println(prefix + match);
                        matchedAny = true;
                    }
                } else if (shouldColor) {
                    List<int[]> spans = findMatchSpans(line, pattern);
                    if (!spans.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        sb.append(prefix);
                        int lastIdx = 0;
                        for (int[] span : spans) {
                            sb.append(line, lastIdx, span[0]);
                            sb.append("\033[01;31m");
                            sb.append(line, span[0], span[1]);
                            sb.append("\033[m");
                            lastIdx = span[1];
                        }
                        sb.append(line.substring(lastIdx));
                        System.out.println(sb.toString());
                        matchedAny = true;
                    }
                } else {
                    if (matchPattern(line, pattern)) {
                        System.out.println(prefix + line);
                        matchedAny = true;
                    }
                }
            }
            scanner.close();
        }

        if (matchedAny) {
            System.exit(0);
        } else {
            System.exit(1);
        }
    }

    private static void collectFiles(File dir, String displayPath, List<FileInputPath> collected) {
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        for (File child : files) {
            String childDisplay = (displayPath.endsWith("/") || displayPath.endsWith("\\"))
                ? displayPath + child.getName()
                : displayPath + "/" + child.getName();
            childDisplay = childDisplay.replace('\\', '/');
            if (child.isDirectory()) {
                collectFiles(child, childDisplay, collected);
            } else if (child.isFile()) {
                collected.add(new FileInputPath(childDisplay, child));
            }
        }
    }

    private static boolean isStdoutTty() {
        String env = System.getenv("IS_TTY");
        if (env != null) {
            return "true".equalsIgnoreCase(env);
        }
        return System.console() != null;
    }

    interface Token {
        boolean matches(char c);
    }

    static class LiteralToken implements Token {
        private final char ch;
        public LiteralToken(char ch) {
            this.ch = ch;
        }
        @Override
        public boolean matches(char c) {
            return this.ch == c;
        }
    }

    static class DigitToken implements Token {
        @Override
        public boolean matches(char c) {
            return Character.isDigit(c);
        }
    }

    static class WordToken implements Token {
        @Override
        public boolean matches(char c) {
            return Character.isLetterOrDigit(c) || c == '_';
        }
    }

    static class WildcardToken implements Token {
        @Override
        public boolean matches(char c) {
            return c != '\n';
        }
    }

    static class PositiveGroupToken implements Token {
        private final Set<Character> chars;
        public PositiveGroupToken(Set<Character> chars) {
            this.chars = chars;
        }
        @Override
        public boolean matches(char c) {
            return chars.contains(c);
        }
    }

    static class NegativeGroupToken implements Token {
        private final Set<Character> chars;
        public NegativeGroupToken(Set<Character> chars) {
            this.chars = chars;
        }
        @Override
        public boolean matches(char c) {
            return !chars.contains(c);
        }
    }

    static class Quantifier {
        final int min;
        final int max;

        Quantifier(int min, int max) {
            this.min = min;
            this.max = max;
        }

        static final Quantifier EXACTLY_ONE = new Quantifier(1, 1);
        static final Quantifier ZERO_OR_ONE = new Quantifier(0, 1);
        static final Quantifier ONE_OR_MORE = new Quantifier(1, Integer.MAX_VALUE);
        static final Quantifier ZERO_OR_MORE = new Quantifier(0, Integer.MAX_VALUE);

        static Quantifier exact(int n) {
            return new Quantifier(n, n);
        }

        static Quantifier range(int min, int max) {
            return new Quantifier(min, max);
        }

        static Quantifier atLeast(int min) {
            return new Quantifier(min, Integer.MAX_VALUE);
        }
    }

    interface PatternNode {}

    static class TokenNode implements PatternNode {
        final Token token;
        final Quantifier quantifier;

        TokenNode(Token token, Quantifier quantifier) {
            this.token = token;
            this.quantifier = quantifier;
        }
    }

    static class GroupNode implements PatternNode {
        final List<List<PatternNode>> branches;
        final Quantifier quantifier;
        final int groupId;

        GroupNode(List<List<PatternNode>> branches, Quantifier quantifier, int groupId) {
            this.branches = branches;
            this.quantifier = quantifier;
            this.groupId = groupId;
        }
    }

    static class BackreferenceNode implements PatternNode {
        final int groupNum;
        final Quantifier quantifier;

        BackreferenceNode(int groupNum, Quantifier quantifier) {
            this.groupNum = groupNum;
            this.quantifier = quantifier;
        }
    }

    static class Parser {
        private final String pattern;
        private int pos;
        private int nextGroupId = 1;

        Parser(String pattern) {
            this.pattern = pattern;
            this.pos = 0;
        }

        List<PatternNode> parse() {
            return parseSequence(false);
        }

        private List<PatternNode> parseSequence(boolean insideGroup) {
            List<PatternNode> nodes = new ArrayList<>();
            while (pos < pattern.length()) {
                char c = pattern.charAt(pos);
                if (insideGroup && (c == ')' || c == '|')) {
                    break;
                }

                if (c == '(') {
                    pos++;
                    int gid = nextGroupId++;
                    List<List<PatternNode>> branches = new ArrayList<>();
                    while (true) {
                        branches.add(parseSequence(true));
                        if (pos < pattern.length() && pattern.charAt(pos) == '|') {
                            pos++;
                        } else {
                            break;
                        }
                    }
                    if (pos < pattern.length() && pattern.charAt(pos) == ')') {
                        pos++;
                    }
                    Quantifier q = parseQuantifier();
                    nodes.add(new GroupNode(branches, q, gid));
                } else if (c == '\\') {
                    if (pos + 1 < pattern.length() && Character.isDigit(pattern.charAt(pos + 1))) {
                        pos++;
                        int groupNum = 0;
                        while (pos < pattern.length() && Character.isDigit(pattern.charAt(pos))) {
                            groupNum = groupNum * 10 + (pattern.charAt(pos) - '0');
                            pos++;
                        }
                        Quantifier q = parseQuantifier();
                        nodes.add(new BackreferenceNode(groupNum, q));
                    } else {
                        Token token = parseToken();
                        Quantifier q = parseQuantifier();
                        nodes.add(new TokenNode(token, q));
                    }
                } else {
                    Token token = parseToken();
                    Quantifier q = parseQuantifier();
                    nodes.add(new TokenNode(token, q));
                }
            }
            return nodes;
        }

        private Token parseToken() {
            char c = pattern.charAt(pos);
            if (c == '\\') {
                pos++;
                char escaped = pattern.charAt(pos++);
                if (escaped == 'd') {
                    return new DigitToken();
                } else if (escaped == 'w') {
                    return new WordToken();
                } else {
                    return new LiteralToken(escaped);
                }
            } else if (c == '[') {
                pos++;
                boolean isNegative = false;
                if (pos < pattern.length() && pattern.charAt(pos) == '^') {
                    isNegative = true;
                    pos++;
                }
                Set<Character> chars = new HashSet<>();
                while (pos < pattern.length() && pattern.charAt(pos) != ']') {
                    chars.add(pattern.charAt(pos++));
                }
                if (pos < pattern.length() && pattern.charAt(pos) == ']') {
                    pos++;
                }
                return isNegative ? new NegativeGroupToken(chars) : new PositiveGroupToken(chars);
            } else if (c == '.') {
                pos++;
                return new WildcardToken();
            } else {
                pos++;
                return new LiteralToken(c);
            }
        }

        private Quantifier parseQuantifier() {
            if (pos < pattern.length()) {
                char next = pattern.charAt(pos);
                if (next == '+') {
                    pos++;
                    return Quantifier.ONE_OR_MORE;
                } else if (next == '?') {
                    pos++;
                    return Quantifier.ZERO_OR_ONE;
                } else if (next == '*') {
                    pos++;
                    return Quantifier.ZERO_OR_MORE;
                } else if (next == '{') {
                    int closeIdx = pattern.indexOf('}', pos);
                    if (closeIdx != -1) {
                        String content = pattern.substring(pos + 1, closeIdx);
                        if (content.contains(",")) {
                            String[] parts = content.split(",", -1);
                            try {
                                int min = Integer.parseInt(parts[0].trim());
                                if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                                    int max = Integer.parseInt(parts[1].trim());
                                    pos = closeIdx + 1;
                                    return Quantifier.range(min, max);
                                } else {
                                    pos = closeIdx + 1;
                                    return Quantifier.atLeast(min);
                                }
                            } catch (NumberFormatException ignored) {}
                        } else {
                            try {
                                int n = Integer.parseInt(content.trim());
                                pos = closeIdx + 1;
                                return Quantifier.exact(n);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
            return Quantifier.EXACTLY_ONE;
        }
    }

    public static boolean matchPattern(String inputLine, String pattern) {
        return findFirstMatch(inputLine, pattern) != null;
    }

    public static String findFirstMatch(String inputLine, String pattern) {
        List<int[]> spans = findMatchSpans(inputLine, pattern);
        return spans.isEmpty() ? null : inputLine.substring(spans.get(0)[0], spans.get(0)[1]);
    }

    public static List<String> findAllMatches(String inputLine, String pattern) {
        List<String> results = new ArrayList<>();
        List<int[]> spans = findMatchSpans(inputLine, pattern);
        for (int[] span : spans) {
            results.add(inputLine.substring(span[0], span[1]));
        }
        return results;
    }

    public static List<int[]> findMatchSpans(String inputLine, String pattern) {
        List<int[]> spans = new ArrayList<>();
        boolean anchorStart = false;
        boolean anchorEnd = false;
        String activePattern = pattern;

        if (activePattern.startsWith("^")) {
            anchorStart = true;
            activePattern = activePattern.substring(1);
        }
        if (activePattern.endsWith("$")) {
            anchorEnd = true;
            activePattern = activePattern.substring(0, activePattern.length() - 1);
        }

        Parser parser = new Parser(activePattern);
        List<PatternNode> nodes = parser.parse();

        if (nodes.isEmpty()) {
            if (anchorEnd && !inputLine.isEmpty()) {
                return spans;
            }
            if (anchorStart) {
                spans.add(new int[]{0, 0});
                return spans;
            }
            for (int i = 0; i <= inputLine.length(); i++) {
                spans.add(new int[]{i, i});
            }
            return spans;
        }

        if (anchorStart) {
            int end = matchEndNodesAt(inputLine, 0, nodes, 0, anchorEnd, new HashMap<>());
            if (end != -1) {
                spans.add(new int[]{0, end});
            }
            return spans;
        }

        int start = 0;
        while (start <= inputLine.length()) {
            int end = matchEndNodesAt(inputLine, start, nodes, 0, anchorEnd, new HashMap<>());
            if (end != -1) {
                spans.add(new int[]{start, end});
                if (anchorEnd) {
                    break;
                }
                if (end > start) {
                    start = end;
                } else {
                    start = start + 1;
                }
            } else {
                start++;
            }
        }

        return spans;
    }

    static class MatchResult {
        final int endPos;
        final Map<Integer, String> capturedGroups;

        MatchResult(int endPos, Map<Integer, String> capturedGroups) {
            this.endPos = endPos;
            this.capturedGroups = capturedGroups;
        }
    }

    private static int matchEndNodesAt(
        String inputLine,
        int textIdx,
        List<PatternNode> nodes,
        int nodeIdx,
        boolean anchorEnd,
        Map<Integer, String> capturedGroups
    ) {
        if (nodeIdx == nodes.size()) {
            if (anchorEnd) {
                return textIdx == inputLine.length() ? textIdx : -1;
            }
            return textIdx;
        }

        PatternNode current = nodes.get(nodeIdx);

        if (current instanceof TokenNode) {
            TokenNode tn = (TokenNode) current;
            Quantifier q = tn.quantifier;
            int count = 0;
            while (textIdx + count < inputLine.length() && count < q.max && tn.token.matches(inputLine.charAt(textIdx + count))) {
                count++;
            }
            if (count < q.min) {
                return -1;
            }
            for (int len = count; len >= q.min; len--) {
                int res = matchEndNodesAt(inputLine, textIdx + len, nodes, nodeIdx + 1, anchorEnd, capturedGroups);
                if (res != -1) {
                    return res;
                }
            }
            return -1;
        } else if (current instanceof GroupNode) {
            GroupNode gn = (GroupNode) current;
            for (List<PatternNode> branch : gn.branches) {
                List<MatchResult> branchResults = new ArrayList<>();
                findBranchMatches(inputLine, textIdx, branch, 0, capturedGroups, branchResults);
                for (MatchResult mr : branchResults) {
                    Map<Integer, String> newCaptured = new HashMap<>(mr.capturedGroups);
                    newCaptured.put(gn.groupId, inputLine.substring(textIdx, mr.endPos));
                    int res = matchEndNodesAt(inputLine, mr.endPos, nodes, nodeIdx + 1, anchorEnd, newCaptured);
                    if (res != -1) {
                        return res;
                    }
                }
            }
            if (gn.quantifier.min == 0) {
                return matchEndNodesAt(inputLine, textIdx, nodes, nodeIdx + 1, anchorEnd, capturedGroups);
            }
            return -1;
        } else if (current instanceof BackreferenceNode) {
            BackreferenceNode bn = (BackreferenceNode) current;
            String captured = capturedGroups.get(bn.groupNum);
            if (captured == null) {
                return -1;
            }
            Quantifier q = bn.quantifier;
            if (captured.isEmpty()) {
                return matchEndNodesAt(inputLine, textIdx, nodes, nodeIdx + 1, anchorEnd, capturedGroups);
            }
            int count = 0;
            while (textIdx + (count + 1) * captured.length() <= inputLine.length() && count < q.max
                    && inputLine.startsWith(captured, textIdx + count * captured.length())) {
                count++;
            }
            if (count < q.min) {
                return -1;
            }
            for (int rep = count; rep >= q.min; rep--) {
                int res = matchEndNodesAt(inputLine, textIdx + rep * captured.length(), nodes, nodeIdx + 1, anchorEnd, capturedGroups);
                if (res != -1) {
                    return res;
                }
            }
            return -1;
        }
        return -1;
    }

    private static void findBranchMatches(
        String inputLine,
        int textIdx,
        List<PatternNode> branchNodes,
        int bIdx,
        Map<Integer, String> capturedGroups,
        List<MatchResult> results
    ) {
        if (bIdx == branchNodes.size()) {
            results.add(new MatchResult(textIdx, new HashMap<>(capturedGroups)));
            return;
        }

        PatternNode current = branchNodes.get(bIdx);
        if (current instanceof TokenNode) {
            TokenNode tn = (TokenNode) current;
            Quantifier q = tn.quantifier;
            int count = 0;
            while (textIdx + count < inputLine.length() && count < q.max && tn.token.matches(inputLine.charAt(textIdx + count))) {
                count++;
            }
            if (count >= q.min) {
                for (int len = count; len >= q.min; len--) {
                    findBranchMatches(inputLine, textIdx + len, branchNodes, bIdx + 1, capturedGroups, results);
                }
            }
        } else if (current instanceof GroupNode) {
            GroupNode gn = (GroupNode) current;
            for (List<PatternNode> branch : gn.branches) {
                List<MatchResult> branchResults = new ArrayList<>();
                findBranchMatches(inputLine, textIdx, branch, 0, capturedGroups, branchResults);
                for (MatchResult mr : branchResults) {
                    Map<Integer, String> newCaptured = new HashMap<>(mr.capturedGroups);
                    newCaptured.put(gn.groupId, inputLine.substring(textIdx, mr.endPos));
                    findBranchMatches(inputLine, mr.endPos, branchNodes, bIdx + 1, newCaptured, results);
                }
            }
            if (gn.quantifier.min == 0) {
                findBranchMatches(inputLine, textIdx, branchNodes, bIdx + 1, capturedGroups, results);
            }
        } else if (current instanceof BackreferenceNode) {
            BackreferenceNode bn = (BackreferenceNode) current;
            String captured = capturedGroups.get(bn.groupNum);
            if (captured != null) {
                Quantifier q = bn.quantifier;
                if (captured.isEmpty()) {
                    findBranchMatches(inputLine, textIdx, branchNodes, bIdx + 1, capturedGroups, results);
                } else {
                    int count = 0;
                    while (textIdx + (count + 1) * captured.length() <= inputLine.length() && count < q.max
                            && inputLine.startsWith(captured, textIdx + count * captured.length())) {
                        count++;
                    }
                    if (count >= q.min) {
                        for (int rep = count; rep >= q.min; rep--) {
                            findBranchMatches(inputLine, textIdx + rep * captured.length(), branchNodes, bIdx + 1, capturedGroups, results);
                        }
                    }
                }
            }
        }
    }
}
