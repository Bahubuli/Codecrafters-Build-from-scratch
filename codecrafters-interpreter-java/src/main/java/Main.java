import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Main {
    public static boolean hadError = false;

    public static void error(Token token, String message) {
        hadError = true;
        if (token.type == TokenType.EOF) {
            System.err.println("[line " + token.line + "] Error at end: " + message);
        } else {
            System.err.println("[line " + token.line + "] Error at '" + token.lexeme + "': " + message);
        }
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: ./your_program.sh <command> <filename>");
            System.exit(1);
        }

        String command = args[0];
        String filename = args[1];

        String fileContents = "";
        try {
            fileContents = Files.readString(Path.of(filename));
        } catch (IOException e) {
            System.err.println("Error reading file: " + e.getMessage());
            System.exit(1);
        }

        if (command.equals("tokenize")) {
            Scanner scanner = new Scanner(fileContents);
            List<Token> tokens = scanner.scanTokens();

            for (Token token : tokens) {
                System.out.println(token);
            }

            if (scanner.hasError()) {
                System.exit(65);
            }
        } else if (command.equals("parse")) {
            Scanner scanner = new Scanner(fileContents);
            List<Token> tokens = scanner.scanTokens();

            if (scanner.hasError()) {
                System.exit(65);
            }

            Parser parser = new Parser(tokens);
            Expr expression = parser.parse();

            if (parser.hasError() || expression == null) {
                System.exit(65);
            }

            System.out.println(new AstPrinter().print(expression));
        } else if (command.equals("evaluate")) {
            Scanner scanner = new Scanner(fileContents);
            List<Token> tokens = scanner.scanTokens();

            if (scanner.hasError()) {
                System.exit(65);
            }

            Parser parser = new Parser(tokens);
            Expr expression = parser.parse();

            if (parser.hasError() || expression == null) {
                System.exit(65);
            }

            Interpreter interpreter = new Interpreter();
            interpreter.interpret(expression);

            if (interpreter.hasRuntimeError()) {
                System.exit(70);
            }
        } else if (command.equals("run")) {
            Scanner scanner = new Scanner(fileContents);
            List<Token> tokens = scanner.scanTokens();

            if (scanner.hasError()) {
                System.exit(65);
            }

            Parser parser = new Parser(tokens);
            List<Stmt> statements = parser.parseStatements();

            if (parser.hasError()) {
                System.exit(65);
            }

            Interpreter interpreter = new Interpreter();
            Resolver resolver = new Resolver(interpreter);
            resolver.resolve(statements);

            if (hadError) {
                System.exit(65);
            }

            interpreter.interpret(statements);

            if (interpreter.hasRuntimeError()) {
                System.exit(70);
            }
        } else {
            System.err.println("Unknown command: " + command);
            System.exit(1);
        }
    }
}
