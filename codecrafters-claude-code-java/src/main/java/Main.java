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
import java.util.List;
import java.util.Map;

public class Main {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
                .model("anthropic/claude-haiku-4.5")
                .addUserMessage(prompt)
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
}
