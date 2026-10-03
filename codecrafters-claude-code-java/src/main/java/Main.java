import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static class Skill {
        final String name;
        final String description;
        final String body;
        final String dirName;

        Skill(String name, String description, String body, String dirName) {
            this.name = name;
            this.description = description;
            this.body = body;
            this.dirName = dirName;
        }
    }

    public static void main(String[] args) {
        if (args.length < 2 || !"-p".equals(args[0])) {
            System.err.println("Usage: program -p <prompt>");
            System.exit(1);
        }

        String prompt = args[1];

        String apiKey = System.getenv("OPENROUTER_API_KEY");
        String baseUrl = System.getenv("OPENROUTER_BASE_URL");
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "https://openrouter.ai/api/v1";
        }

        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("OPENROUTER_API_KEY is not set");
        }

        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        FunctionParameters readParams = FunctionParameters.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                        "file_path", Map.of(
                                "type", "string",
                                "description", "The path to the file to read"
                        )
                )))
                .putAdditionalProperty("required", JsonValue.from(List.of("file_path")))
                .build();

        ChatCompletionTool readTool = ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("Read")
                        .description("Read and return the contents of a file")
                        .parameters(readParams)
                        .build())
                .build();

        FunctionParameters writeParams = FunctionParameters.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                        "file_path", Map.of(
                                "type", "string",
                                "description", "The path of the file to write to"
                        ),
                        "content", Map.of(
                                "type", "string",
                                "description", "The content to write to the file"
                        )
                )))
                .putAdditionalProperty("required", JsonValue.from(List.of("file_path", "content")))
                .build();

        ChatCompletionTool writeTool = ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("Write")
                        .description("Write content to a file")
                        .parameters(writeParams)
                        .build())
                .build();

        FunctionParameters bashParams = FunctionParameters.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                        "command", Map.of(
                                "type", "string",
                                "description", "The command to execute"
                        )
                )))
                .putAdditionalProperty("required", JsonValue.from(List.of("command")))
                .build();

        ChatCompletionTool bashTool = ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("Bash")
                        .description("Execute a shell command")
                        .parameters(bashParams)
                        .build())
                .build();

        ChatCompletionCreateParams.Builder createParamsBuilder = ChatCompletionCreateParams.builder()
                .model("anthropic/claude-haiku-4.5");

        List<Skill> skills = loadSkills();
        if (!skills.isEmpty()) {
            StringBuilder systemPrompt = new StringBuilder();
            systemPrompt.append("You have access to the following skills:\n\n");
            for (int i = 0; i < skills.size(); i++) {
                Skill s = skills.get(i);
                systemPrompt.append("- ").append(s.name).append(": ").append(s.description);
                if (i < skills.size() - 1) {
                    systemPrompt.append("\n");
                }
            }
            createParamsBuilder.addSystemMessage(systemPrompt.toString());
        }

        String userPrompt = prompt;
        if (prompt.startsWith("/")) {
            String trimmedPrompt = prompt.trim();
            String command = trimmedPrompt.substring(1).split("\\s+")[0];
            String argsPart = trimmedPrompt.substring(1 + command.length()).trim();

            for (Skill skill : skills) {
                if (skill.name.equalsIgnoreCase(command) || skill.dirName.equalsIgnoreCase(command)) {
                    userPrompt = substitutePlaceholders(skill.body, argsPart);
                    break;
                }
            }
        }

        createParamsBuilder.addUserMessage(userPrompt)
                .addTool(readTool)
                .addTool(writeTool)
                .addTool(bashTool);

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        while (true) {
            ChatCompletion response = client.chat().completions().create(createParamsBuilder.build());

            if (response.choices().isEmpty()) {
                throw new RuntimeException("no choices in response");
            }

            ChatCompletionMessage assistantMessage = response.choices().get(0).message();
            createParamsBuilder.addMessage(assistantMessage);

            if (assistantMessage.toolCalls().isEmpty() || assistantMessage.toolCalls().get().isEmpty()) {
                System.out.print(assistantMessage.content().orElse(""));
                break;
            }

            for (ChatCompletionMessageToolCall toolCall : assistantMessage.toolCalls().get()) {
                String toolCallId = toolCall.id();
                String funcName = toolCall.function().name();
                String arguments = toolCall.function().arguments();

                String result;
                if ("Read".equalsIgnoreCase(funcName) || "read_file".equalsIgnoreCase(funcName)) {
                    try {
                        JsonNode argsNode = OBJECT_MAPPER.readTree(arguments);
                        String filePath = argsNode.get("file_path").asText();
                        result = Files.readString(Path.of(filePath));
                    } catch (IOException e) {
                        result = "Error reading file: " + e.getMessage();
                    }
                } else if ("Write".equalsIgnoreCase(funcName) || "write_file".equalsIgnoreCase(funcName) || "WriteFile".equalsIgnoreCase(funcName)) {
                    try {
                        JsonNode argsNode = OBJECT_MAPPER.readTree(arguments);
                        String filePath = argsNode.get("file_path").asText();
                        String content = argsNode.has("content") ? argsNode.get("content").asText() : "";
                        Path path = Path.of(filePath);
                        if (path.getParent() != null) {
                            Files.createDirectories(path.getParent());
                        }
                        Files.writeString(path, content);
                        result = "File written successfully: " + filePath;
                    } catch (IOException e) {
                        result = "Error writing file: " + e.getMessage();
                    }
                } else if ("Bash".equalsIgnoreCase(funcName) || "bash".equalsIgnoreCase(funcName) || "RunBashCommand".equalsIgnoreCase(funcName) || "run_bash_command".equalsIgnoreCase(funcName)) {
                    try {
                        JsonNode argsNode = OBJECT_MAPPER.readTree(arguments);
                        String command = argsNode.get("command").asText();
                        ProcessBuilder pb;
                        if (isWindows) {
                            pb = new ProcessBuilder("cmd.exe", "/c", command);
                        } else {
                            pb = new ProcessBuilder("bash", "-c", command);
                        }
                        Process process = pb.start();
                        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                        int exitCode = process.waitFor();

                        StringBuilder sb = new StringBuilder();
                        if (!stdout.isEmpty()) {
                            sb.append(stdout);
                        }
                        if (!stderr.isEmpty()) {
                            if (sb.length() > 0 && !stdout.endsWith("\n")) {
                                sb.append("\n");
                            }
                            sb.append(stderr);
                        }
                        result = sb.toString();
                    } catch (Exception e) {
                        result = "Error executing bash command: " + e.getMessage();
                    }
                } else {
                    result = "Error: Unknown tool " + funcName;
                }

                createParamsBuilder.addMessage(
                        ChatCompletionToolMessageParam.builder()
                                .toolCallId(toolCallId)
                                .content(result)
                                .build()
                );
            }
        }
    }

    private static String substitutePlaceholders(String body, String rawArgs) {
        if (body == null) return "";
        String trimmedArgs = rawArgs == null ? "" : rawArgs.trim();
        String[] argTokens = trimmedArgs.isEmpty() ? new String[0] : trimmedArgs.split("\\s+");

        // 1. $ARGUMENTS[n]
        Pattern pArray = Pattern.compile("\\$ARGUMENTS\\[(\\d+)\\]");
        Matcher mArray = pArray.matcher(body);
        StringBuilder sb1 = new StringBuilder();
        while (mArray.find()) {
            int idx = Integer.parseInt(mArray.group(1));
            String val = (idx >= 0 && idx < argTokens.length) ? argTokens[idx] : "";
            mArray.appendReplacement(sb1, Matcher.quoteReplacement(val));
        }
        mArray.appendTail(sb1);
        String step1 = sb1.toString();

        // 2. $ARGUMENTS
        String step2 = step1.replace("$ARGUMENTS", trimmedArgs);

        // 3. $n (e.g. $0, $1)
        Pattern pPos = Pattern.compile("\\$(\\d+)");
        Matcher mPos = pPos.matcher(step2);
        StringBuilder sb3 = new StringBuilder();
        while (mPos.find()) {
            int idx = Integer.parseInt(mPos.group(1));
            String val = (idx >= 0 && idx < argTokens.length) ? argTokens[idx] : "";
            mPos.appendReplacement(sb3, Matcher.quoteReplacement(val));
        }
        mPos.appendTail(sb3);

        return sb3.toString();
    }

    private static List<Skill> loadSkills() {
        List<Skill> skills = new ArrayList<>();
        Path skillsDir = Path.of(".claude", "skills");
        if (!Files.exists(skillsDir) || !Files.isDirectory(skillsDir)) {
            return skills;
        }

        try (var stream = Files.list(skillsDir)) {
            List<Path> dirs = stream.filter(Files::isDirectory).sorted().toList();
            for (Path dir : dirs) {
                Path skillFile = dir.resolve("SKILL.md");
                if (!Files.exists(skillFile) || !Files.isRegularFile(skillFile)) {
                    continue;
                }
                try {
                    String content = Files.readString(skillFile);
                    Skill skill = parseSkill(dir.getFileName().toString(), content);
                    if (skill != null) {
                        skills.add(skill);
                    }
                } catch (IOException e) {
                    System.err.println("Error reading skill file: " + skillFile + " : " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Error listing skills dir: " + e.getMessage());
        }

        skills.sort(Comparator.comparing(s -> s.name));
        return skills;
    }

    private static Skill parseSkill(String folderName, String content) {
        String[] lines = content.split("\\r?\\n");
        int firstDelimiter = -1;
        int secondDelimiter = -1;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.equals("---")) {
                if (firstDelimiter == -1) {
                    firstDelimiter = i;
                } else {
                    secondDelimiter = i;
                    break;
                }
            }
        }

        if (firstDelimiter == -1 || secondDelimiter == -1 || secondDelimiter <= firstDelimiter) {
            return null;
        }

        String name = folderName;
        String description = "";

        String currentKey = null;
        StringBuilder currentVal = new StringBuilder();

        for (int i = firstDelimiter + 1; i < secondDelimiter; i++) {
            String line = lines[i];
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0 && !line.startsWith(" ") && !line.startsWith("\t")) {
                if (currentKey != null) {
                    if (currentKey.equals("name") && !currentVal.toString().trim().isEmpty()) {
                        name = cleanYamlValue(currentVal.toString().trim());
                    } else if (currentKey.equals("description")) {
                        description = cleanYamlValue(currentVal.toString().trim());
                    }
                }
                currentKey = line.substring(0, colonIdx).trim();
                currentVal = new StringBuilder(line.substring(colonIdx + 1).trim());
            } else if (currentKey != null) {
                currentVal.append(" ").append(line.trim());
            }
        }

        if (currentKey != null) {
            if (currentKey.equals("name") && !currentVal.toString().trim().isEmpty()) {
                name = cleanYamlValue(currentVal.toString().trim());
            } else if (currentKey.equals("description")) {
                description = cleanYamlValue(currentVal.toString().trim());
            }
        }

        StringBuilder bodyBuilder = new StringBuilder();
        for (int i = secondDelimiter + 1; i < lines.length; i++) {
            bodyBuilder.append(lines[i]);
            if (i < lines.length - 1) {
                bodyBuilder.append("\n");
            }
        }
        String body = bodyBuilder.toString().trim();

        return new Skill(name, description, body, folderName);
    }

    private static String cleanYamlValue(String val) {
        if (val == null) return "";
        val = val.trim();
        if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
            if (val.length() >= 2) {
                val = val.substring(1, val.length() - 1);
            }
        }
        return val;
    }
}
