package uk.ac.cam.tfc_server.util;

import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;

// Constants used in tfc server platform

public class Log {

    // get current local time as "YYYY-MM-DD-hh-mm-ss"
  public static String local_datetime_string()
    {
        LocalDateTime local_time = LocalDateTime.now();
        return local_time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"));
    }

    // print msg to stderr prepended with local time
  public static void log_err(String msg)
    {
        System.err.println(local_datetime_string()+": "+msg);
    }
    
    public static void log(String msg) {
        System.out.println(local_datetime_string()+ ": "+msg );
    }
}
