package soloMapling;

import soloMapling.Environment.RuntimeData;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;


/*
Shift rightclick in NostalgiaStory direction
Copy below into powershell

powershell -Command "Get-Content -Path 'logs/BotLog.txt' -Wait"

 */

public class BotLogger {
    static final boolean log = true;
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void log(String message) {
        if (!log) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String timestamp = now.format(formatter);
        String logMessage = String.format("[%s]: %s", timestamp, message);

        try {
            Path logFile = RuntimeData.ensureParent(RuntimeData.botLog());
            try (PrintWriter out = new PrintWriter(new FileWriter(logFile.toFile(), true))) {
                out.println(logMessage);
            }
        } catch (IOException e) {
            System.err.println("Error writing to log file: " + e.getMessage());
        }
    }
}
