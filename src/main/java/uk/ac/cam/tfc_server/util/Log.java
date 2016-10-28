package uk.ac.cam.tfc_server.util;

import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;

// Basic logging utility, may replace with log4j at some point...

public class Log {

    public int level;

    public Log(int l)
    {
        set_level(l);
    }

    public void set_level(int l)
    {
        level = l;
    }
    
    // get current local time as "YYYY-MM-DD hh:mm:ss"
    public static String local_datetime_string()
    {
        LocalDateTime local_time = LocalDateTime.now();
        return local_time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    // print msg to stderr prepended with local time
    public static void log_err(String msg)
    {
        System.out.println(local_datetime_string()+" ERROR: "+msg);
    }
    
    public void log(int l, String msg) {
        if (l >= level)
            {
                System.out.println(local_datetime_string()+ ": "+msg );
            }
    }
}
