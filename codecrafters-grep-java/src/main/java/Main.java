import java.io.IOException;
import java.util.ArrayList;
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

    public static void main(String[] args) {
        boolean onlyMatching = false;
        ColorMode colorMode = ColorMode.NEVER;
        String pattern = null;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-o") || args[i].equals("--only-matching")) {
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
            }
        }

        if (pattern == null) {
            System.out.println("Usage: ./your_program.sh [--color=always|auto|never] [-o] -E <pattern>");
            System.exit(1);
        }

        boolean shouldColor = (colorMode == ColorMode.ALWAYS) || (colorMode == ColorMode.AUTO && isStdoutTty());

        Scanner scanner = new Scanner(System.in);
        boolean matchedAny = false;

        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            if (onlyMatching) {
                List<String> matches = findAllMatches(line, pattern);
                for (String match : matches) {
                    System.out.println(match);
                    matchedAny = true;
                }
            } else if (shouldColor) {
                List<int[]> spans = findMatchSpans(line, pattern);
                if (!spans.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
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
                    System.out.println(line);
                    matchedAny = true;
                }
            }
        }

        if (matchedAny) {
            System.exit(0);
        } else {
            System.exit(1);
        }
    }

    private static boolean isStdoutTty() {
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

    enum Quantifier {
        EXACTLY_ONE,
        ONE_OR_MORE,
        ZERO_OR_ONE
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

        BackreferenceNode(int groupNum) {
            this.groupNum = groupNum;
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
                        int groupNum = pattern.charAt(pos) - '0';
                        pos++;
                        nodes.add(new BackreferenceNode(groupNum));
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
            if (tn.quantifier == Quantifier.EXACTLY_ONE) {
                if (textIdx < inputLine.length() && tn.token.matches(inputLine.charAt(textIdx))) {
                    return matchEndNodesAt(inputLine, textIdx + 1, nodes, nodeIdx + 1, anchorEnd, capturedGroups);
                }
                return -1;
            } else if (tn.quantifier == Quantifier.ONE_OR_MORE) {
                if (textIdx >= inputLine.length() || !tn.token.matches(inputLine.charAt(textIdx))) {
                    return -1;
                }
                int maxMatch = textIdx;
                while (maxMatch < inputLine.length() && tn.token.matches(inputLine.charAt(maxMatch))) {
                    maxMatch++;
                }
                for (int end = maxMatch; end >= textIdx + 1; end--) {
                    int res = matchEndNodesAt(inputLine, end, nodes, nodeIdx + 1, anchorEnd, capturedGroups);
                    if (res != -1) {
                        return res;
                    }
                }
                return -1;
            } else if (tn.quantifier == Quantifier.ZERO_OR_ONE) {
                if (textIdx < inputLine.length() && tn.token.matches(inputLine.charAt(textIdx))) {
                    int res = matchEndNodesAt(inputLine, textIdx + 1, nodes, nodeIdx + 1, anchorEnd, capturedGroups);
                    if (res != -1) {
                        return res;
                    }
                }
                return matchEndNodesAt(inputLine, textIdx, nodes, nodeIdx + 1, anchorEnd, capturedGroups);
            }
        } else if (current instanceof GroupNode) {
            GroupNode gn = (GroupNode) current;
            for (List<PatternNode> branch : gn.branches) {
                List<Integer> branchEnds = new ArrayList<>();
                findBranchMatches(inputLine, textIdx, branch, 0, capturedGroups, branchEnds);
                for (int endPos : branchEnds) {
                    Map<Integer, String> newCaptured = new HashMap<>(capturedGroups);
                    newCaptured.put(gn.groupId, inputLine.substring(textIdx, endPos));
                    int res = matchEndNodesAt(inputLine, endPos, nodes, nodeIdx + 1, anchorEnd, newCaptured);
                    if (res != -1) {
                        return res;
                    }
                }
            }
            if (gn.quantifier == Quantifier.ZERO_OR_ONE) {
                return matchEndNodesAt(inputLine, textIdx, nodes, nodeIdx + 1, anchorEnd, capturedGroups);
            }
            return -1;
        } else if (current instanceof BackreferenceNode) {
            BackreferenceNode bn = (BackreferenceNode) current;
            String captured = capturedGroups.get(bn.groupNum);
            if (captured == null) {
                return -1;
            }
            if (inputLine.startsWith(captured, textIdx)) {
                return matchEndNodesAt(inputLine, textIdx + captured.length(), nodes, nodeIdx + 1, anchorEnd, capturedGroups);
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
        List<Integer> endPositions
    ) {
        if (bIdx == branchNodes.size()) {
            endPositions.add(textIdx);
            return;
        }

        PatternNode current = branchNodes.get(bIdx);
        if (current instanceof TokenNode) {
            TokenNode tn = (TokenNode) current;
            if (tn.quantifier == Quantifier.EXACTLY_ONE) {
                if (textIdx < inputLine.length() && tn.token.matches(inputLine.charAt(textIdx))) {
                    findBranchMatches(inputLine, textIdx + 1, branchNodes, bIdx + 1, capturedGroups, endPositions);
                }
            } else if (tn.quantifier == Quantifier.ONE_OR_MORE) {
                if (textIdx >= inputLine.length() || !tn.token.matches(inputLine.charAt(textIdx))) {
                    return;
                }
                int maxMatch = textIdx;
                while (maxMatch < inputLine.length() && tn.token.matches(inputLine.charAt(maxMatch))) {
                    maxMatch++;
                }
                for (int end = maxMatch; end >= textIdx + 1; end--) {
                    findBranchMatches(inputLine, end, branchNodes, bIdx + 1, capturedGroups, endPositions);
                }
            } else if (tn.quantifier == Quantifier.ZERO_OR_ONE) {
                if (textIdx < inputLine.length() && tn.token.matches(inputLine.charAt(textIdx))) {
                    findBranchMatches(inputLine, textIdx + 1, branchNodes, bIdx + 1, capturedGroups, endPositions);
                }
                findBranchMatches(inputLine, textIdx, branchNodes, bIdx + 1, capturedGroups, endPositions);
            }
        } else if (current instanceof BackreferenceNode) {
            BackreferenceNode bn = (BackreferenceNode) current;
            String captured = capturedGroups.get(bn.groupNum);
            if (captured != null && inputLine.startsWith(captured, textIdx)) {
                findBranchMatches(inputLine, textIdx + captured.length(), branchNodes, bIdx + 1, capturedGroups, endPositions);
            }
        }
    }
}
