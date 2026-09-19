import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class Main {
    public static void main(String[] args) {
        System.err.println("Logs from your program will appear here!");

        int port = 9092;
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            serverSocket.setReuseAddress(true);

            try (Socket clientSocket = serverSocket.accept()) {
                System.err.println("Client connected!");
                DataInputStream in = new DataInputStream(clientSocket.getInputStream());

                int messageSize = in.readInt();
                short apiKey = in.readShort();
                short apiVersion = in.readShort();
                int correlationId = in.readInt();

                System.err.printf("Request: size=%d, apiKey=%d, apiVersion=%d, correlationId=%d%n",
                        messageSize, apiKey, apiVersion, correlationId);

                int remaining = messageSize - (2 + 2 + 4);
                if (remaining > 0) {
                    in.skipBytes(remaining);
                }

                DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream());
                out.writeInt(0); // message_size
                out.writeInt(correlationId); // echo extracted correlation_id
                out.flush();
            }
        } catch (IOException e) {
            System.err.println("IOException: " + e.getMessage());
        }
    }
}
