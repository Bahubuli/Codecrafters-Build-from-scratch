import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
                InputStream in = clientSocket.getInputStream();
                byte[] buffer = new byte[1024];
                int bytesRead = in.read(buffer);
                System.err.println("Read " + bytesRead + " bytes from client");

                DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream());
                out.writeInt(0); // message_size (4 bytes)
                out.writeInt(7); // correlation_id (4 bytes)
                out.flush();
            }
        } catch (IOException e) {
            System.err.println("IOException: " + e.getMessage());
        }
    }
}
