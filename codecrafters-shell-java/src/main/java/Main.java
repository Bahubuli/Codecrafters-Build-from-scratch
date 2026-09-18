import java.util.Arrays;
import java.util.Scanner;

public class Main {
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
            } else {
                System.out.println(input + ": command not found");
            }
        }
    }
}
