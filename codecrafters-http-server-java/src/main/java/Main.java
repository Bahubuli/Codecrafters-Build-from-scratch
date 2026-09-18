import java.io.IOException;
import java.net.ServerSocket;

public class Main {
  public static void main(String[] args) {
    try (ServerSocket serverSocket = new ServerSocket(4221)) {
      serverSocket.setReuseAddress(true);
      serverSocket.accept();
    } catch (IOException e) {
      System.err.println("IOException: " + e.getMessage());
    }
  }
}
