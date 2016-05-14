package uk.ac.cam.tfc_server.util;

// Constants used in tfc server platform

public class Constants {
// module message types
//
    // Zone msg_type values which flow on zone.address
    public static final String ZONE_START = "zone_entry_start"; // vehicle entered zone via start line
    public static final String ZONE_COMPLETION = "zone_completion"; // vehicle exitted zone via finish line
    public static final String ZONE_ENTRY = "zone_entry"; // vehicle entered zone not via start line
    public static final String ZONE_EXIT = "zone_exit"; // vehicle exitted zone not via finish line

    // Manager msg_type values
    public static final String ZONE_SUBSCRIBE = "zone_subscribe"; // request zone to subscribe to feed
    public static final String ZONE_UPDATE = "zone_update"; // request zone to publish a zone_update msg (all completions today)

}
