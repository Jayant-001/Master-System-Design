package miniserver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class Main {
    
    public static void main(String[] args) throws IOException {
        
        // 1. LISTEN: ask the OS to open port 8080 for this program
        try (ServerSocket serverSocket = new ServerSocket(8080)) {
            System.out.println("Listening on port 8080");

            while(true) {
                System.out.println("Waiting for a client...");

                // 2. ACCEPT: wait here until a client connects, then get its socket
                try (Socket client = serverSocket.accept(); 
                    BufferedReader in = new BufferedReader(
                        new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8)); 
                    PrintWriter out = new PrintWriter(
                        client.getOutputStream(), true, StandardCharsets.UTF_8)) {

                            System.out.println("Accepted " + client.getRemoteSocketAddress());

                            // 3. READ: wait here until the client sends a full line (ends with Enter)
                            String line = in.readLine();
                            System.out.println("Received: " + line);

                            // 4. WRITE: send it back
                            out.println("echo: " + line);
                        }

                // 5. CLOSE: try-with-resources closes the socket here automatically
                System.out.println("Closed connection");
            }
        }

    }
}
