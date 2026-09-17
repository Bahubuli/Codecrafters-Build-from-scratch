import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class Main {
  public static void main(String[] args) {
    // You can use print statements as follows for debugging, they'll be visible when running tests.
    System.out.println("Logs from your program will appear here!");

    int port = 6379;

    try {
      ServerSocket serverSocket = new ServerSocket(port);
      // Since the tester restarts your program quite often, setting SO_REUSEADDR
      // ensures that we don't run into 'Address already in use' errors
      serverSocket.setReuseAddress(true);

      while (true) {
        // main thread does ONLY this: accept, hand off to a worker, loop back
        Socket clientSocket = serverSocket.accept();

        new Thread(() -> {
          try {
            OutputStream out = clientSocket.getOutputStream();
            InputStream in = clientSocket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));

            // Example incoming command:  *2\r\n$4\r\nECHO\r\n$3\r\nhey\r\n
            // readLine() strips the \r\n, so we get one clean line at a time:
            //   "*2", then "$4", "ECHO", "$3", "hey"
            while (true) {
              String line = reader.readLine();
              if (line == null) break; // client closed the connection

              // RESP array header. line = "*2"
              //   line.substring(1)  -> "2"   (dropped the '*' type marker)
              //   Integer.parseInt   -> 2     (text "2" becomes the number 2)
              // n = how many elements (bulk strings) follow.
              int n = Integer.parseInt(line.substring(1));

              String[] parts = new String[n];
              for (int i = 0; i < n; i++) {
                // Each element is two lines: a "$<len>" header, then the payload.
                reader.readLine();            // "$4" length line — skipped (readLine already framed it)
                parts[i] = reader.readLine(); // the payload itself: "ECHO", then "hey"
              }
              // After the loop: parts = ["ECHO", "hey"]

              String command = parts[0]; // "ECHO"  (element 0 = command name)
              if (command.equalsIgnoreCase("ECHO")) {
                String arg = parts[1]; // "hey"  (element 1 = the argument to echo back)
                // Encode arg as a RESP bulk string: "$<len>\r\n<arg>\r\n"
                //   arg = "hey", arg.length() = 3  ->  response = "$3\r\nhey\r\n"
                String response = "$" + arg.length() + "\r\n" + arg + "\r\n";
                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.flush();
              } else if (command.equalsIgnoreCase("PING")) {
                out.write("+PONG\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
              }
            }
          } catch (IOException e) {
            System.out.println("IOException: " + e.getMessage());
          }
        }).start();
      }
    } catch (IOException e) {
      System.out.println("IOException: " + e.getMessage());
    }
  }
}
