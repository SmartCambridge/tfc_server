package uk.ac.cam.tfc_server.util;

import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;

// Constants used in tfc server platform

public class Log {

    public static void log(String msg) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        LocalDateTime date_time = LocalDateTime.now();
        
        System.out.println(date_time.format(formatter)+ ": "+msg );
    }
}
