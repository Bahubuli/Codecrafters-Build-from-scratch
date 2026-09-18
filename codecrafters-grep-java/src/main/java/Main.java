import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

public class Main {
    public static void main(String[] args) {
        boolean onlyMatching = false;
        String pattern = null;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-o") || args[i].equals("--only-matching")) {
                onlyMatching = true;
            } else if (args[i].equals("-E")) {
                if (i + 1 < args.length) {
                    pattern = args[++i];
                }
            }
        }

        if (pattern == null) {
            System.out.println("Usage: ./your_program.sh [-o] -E <pattern>");
            System.exit(1);
        }

        Scanner scanner = new Scanner(System.in);
        boolean matchedAny = false;

        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            if (onlyMatching) {
                String match = findFirstMatch(line, pattern);
                if (match != null) {
                    System.out.println(match);
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
        public PositiveGroupToken(String characters) {
            this.chars = new HashSet<>();
            for (char c : characters.toCharArray()) {
                this.chars.add(c);
            }
        }
        @Override
        public boolean matches(char c) {
            return chars.contains(c);
        }
    }

    static class NegativeGroupToken implements Token {
        private final Set<Character> chars;
        public NegativeGroupToken(String characters) {
            this.chars = new HashSet<>();
            for (char c : characters.toCharArray()) {
                this.chars.add(c);
            }
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
        final int groupId;
        final List<List<PatternNode>> alternatives;
        final Quantifier quantifier;

        GroupNode(int groupId, List<List<PatternNode>> alternatives, Quantifier quantifier) {
            this.groupId = groupId;
            this.alternatives = alternatives;
            this.quantifier = quantifier;
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
        private int pos = 0;
        private int nextGroupId = 1;

        Parser(String pattern) {
            this.pattern = pattern;
        }

        List<PatternNode> parse() {
            return parseSequence(-1);
        }

        private List<PatternNode> parseSequence(int stopChar) {
            List<PatternNode> nodes = new ArrayList<>();
            while (pos < pattern.length()) {
                char c = pattern.charAt(pos);
                if (stopChar != -1 && (c == stopChar || c == '|')) {
                    break;
                }

                if (c == '(') {
                    pos++; // consume '('
                    int currentGroupId = nextGroupId++;
                    List<List<PatternNode>> alternatives = new ArrayList<>();

                    while (pos < pattern.length() && pattern.charAt(pos) != ')') {
                        List<PatternNode> altNodes = parseSequence(')');
                        alternatives.add(altNodes);
                        if (pos < pattern.length() && pattern.charAt(pos) == '|') {
                            pos++; // consume '|'
                        }
                    }
                    if (pos < pattern.length() && pattern.charAt(pos) == ')') {
                        pos++; // consume ')'
                    }

                    Quantifier q = parseQuantifier();
                    nodes.add(new GroupNode(currentGroupId, alternatives, q));
                } else if (c == '\\') {
                    if (pos + 1 < pattern.length()) {
                        char next = pattern.charAt(pos + 1);
                        if (Character.isDigit(next) && next != '0') {
                            int groupNum = next - '0';
                            pos += 2;
                            nodes.add(new BackreferenceNode(groupNum));
                        } else if (next == 'd') {
                            pos += 2;
                            Quantifier q = parseQuantifier();
                            nodes.add(new TokenNode(new DigitToken(), q));
                        } else if (next == 'w') {
                            pos += 2;
                            Quantifier q = parseQuantifier();
                            nodes.add(new TokenNode(new WordToken(), q));
                        } else {
                            pos += 2;
                            Quantifier q = parseQuantifier();
                            nodes.add(new TokenNode(new LiteralToken(next), q));
                        }
                    } else {
                        pos++;
                        Quantifier q = parseQuantifier();
                        nodes.add(new TokenNode(new LiteralToken('\\'), q));
                    }
                } else if (c == '.') {
                    pos++;
                    Quantifier q = parseQuantifier();
                    nodes.add(new TokenNode(new WildcardToken(), q));
                } else if (c == '[') {
                    int closing = pattern.indexOf(']', pos + 1);
                    if (closing != -1) {
                        Token token;
                        if (pos + 1 < pattern.length() && pattern.charAt(pos + 1) == '^') {
                            String groupChars = pattern.substring(pos + 2, closing);
                            token = new NegativeGroupToken(groupChars);
                        } else {
                            String groupChars = pattern.substring(pos + 1, closing);
                            token = new PositiveGroupToken(groupChars);
                        }
                        pos = closing + 1;
                        Quantifier q = parseQuantifier();
                        nodes.add(new TokenNode(token, q));
                    } else {
                        pos++;
                        Quantifier q = parseQuantifier();
                        nodes.add(new TokenNode(new LiteralToken(c), q));
                    }
                } else {
                    pos++;
                    Quantifier q = parseQuantifier();
                    nodes.add(new TokenNode(new LiteralToken(c), q));
                }
            }
            return nodes;
        }

        private Quantifier parseQuantifier() {
            if (pos < pattern.length()) {
                char c = pattern.charAt(pos);
                if (c == '+') {
                    pos++;
                    return Quantifier.ONE_OR_MORE;
                } else if (c == '?') {
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
                return null;
            }
            return "";
        }

        if (anchorStart) {
            int end = matchEndNodesAt(inputLine, 0, nodes, 0, anchorEnd, new HashMap<>());
            if (end != -1) {
                return inputLine.substring(0, end);
            }
            return null;
        }

        for (int start = 0; start <= inputLine.length(); start++) {
            int end = matchEndNodesAt(inputLine, start, nodes, 0, anchorEnd, new HashMap<>());
            if (end != -1) {
                return inputLine.substring(start, end);
            }
        }
        return null;
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
            for (List<PatternNode> branch : gn.alternatives) {
                List<Integer> endPositions = new ArrayList<>();
                findBranchMatches(inputLine, textIdx, branch, 0, capturedGroups, endPositions);
                for (int endPos : endPositions) {
                    String captured = inputLine.substring(textIdx, endPos);
                    Map<Integer, String> newCaptured = new HashMap<>(capturedGroups);
                    newCaptured.put(gn.groupId, captured);
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
