import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
public class Main {
  public static void main(String[] args){
    // You can use print statements as follows for debugging, they'll be visible when running tests.
    System.out.println("Logs from your program will appear here!");

     //Uncomment the code below to pass the first stage
       ServerSocket serverSocket = null;
       Socket clientSocket = null;
       int port = 6379;
       try {
         serverSocket = new ServerSocket(port);
         // Since the tester restarts your program quite often, setting SO_REUSEADDR
         // ensures that we don't run into 'Address already in use' errors
         serverSocket.setReuseAddress(true);

         while(true){

          Socket clientSocketx = serverSocket.accept();

          new Thread(()->{
            try{
              OutputStream out = clientSocketx.getOutputStream();
              InputStream in = clientSocketx.getInputStream();
              byte[] buffer = new byte[1024];

              while(true){
                // here if connection closed we get -1 
                // otherwise thread is blocked and waits for the input
                int bytesRead = in.read(buffer);
                if(bytesRead==-1) break;
                out.write("+PONG\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
              }
            }
            catch(IOException e){
              System.out.println("IOException: "+ e.getMessage());
            }
          }).start();

         }

         // write PONG response to client 
       } catch (IOException e) {
         System.out.println("IOException: " + e.getMessage());
       } finally {
         try {
           if (clientSocket != null) {
             clientSocket.close();
           }
         } catch (IOException e) {
           System.out.println("IOException: " + e.getMessage());
         }
       }
  } 
}
