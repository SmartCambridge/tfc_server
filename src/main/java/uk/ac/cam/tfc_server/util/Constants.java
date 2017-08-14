package uk.ac.cam.tfc_server.util;

// Constants used in tfc server platform

public class Constants {
// module message types
//
    // FeedPlayer/FeedMaker msg_type values
    public static final String FEED_BUS_POSITION = "feed_bus_position"; // e.g. as derived from GTFS bus
    public static final String FEED_CAR_PARKS  = "feed_car_parks"; // occupancy of multiple car parks
    public static final String FEED_PLAIN  = "feed_plain"; // feed accepted without parsing
    public static final int    FEEDHANDLER_MAX_POST = 1000000; // max feed post in bytes
    
    // Zone msg_type values which flow on zone.address
    public static final String ZONE_START = "zone_entry_start"; // vehicle entered zone via start line
    public static final String ZONE_COMPLETION = "zone_completion"; // vehicle exitted zone via finish line
    public static final String ZONE_ENTRY = "zone_entry"; // vehicle entered zone not via start line
    public static final String ZONE_EXIT = "zone_exit"; // vehicle exitted zone not via finish line
    public static final String ZONE_UPDATE = "zone_update"; // zone_update msg (all completions so far today)
    public static final String ZONE_INFO = "zone_info"; // zone_info msg (zone details, such as boundary polygon)

    // Manager msg_type values
    public static final String ZONE_SUBSCRIBE = "zone_subscribe"; // request zone to subscribe to feed
    public static final String ZONE_UPDATE_REQUEST = "zone_update_request"; // request zone to publish a ZONE_UPDATE msg
    public static final String ZONE_INFO_REQUEST = "zone_info_request"; // request zone to publish a ZONE_INFO msg

// module constants
    public static final int    ZONE_BUFFER_SIZE = 1000; // max number of Zone Completion messages to be stored in buffer

    // browser socket msg_type values
    public static final String SOCKET_ZONE_CONNECT = "zone_connect"; // msg_type zone_plot.hbs initial connect
    public static final String SOCKET_ZONE_MAP_CONNECT = "zone_map_connect"; // msg_type zone_map.hbs initial connect
    public static final String SOCKET_FEED_CONNECT = "feed_connect"; // msg_type feed.hbs initial connect

    // EverynetFeed msg_types
    public static final String EVERYNET_ASCII_DECIMAL = "everynet_ascii_decimal";
    public static final String EVERYNET_ASCII_HEX = "everynet_ascii_hex";
    public static final String EVERYNET_ADEUNIS_TEST = "everynet_adeunis_test";

    // module methods
    public static final String METHOD_ADD_SENSOR = "add_sensor"; // MsgRouter
    public static final String METHOD_REMOVE_SENSOR = "remove_sensor"; // MsgRouter
    public static final String METHOD_ADD_DESTINATION = "add_destination"; // MsgRouter
    public static final String METHOD_REMOVE_DESTINATION = "remove_destination"; // MsgRouter

    // MsgFiler constants - also used in MsgFiler config()
    public static final String FILE_WRITE = "write"; // will overwrite the file
    public static final String FILE_APPEND = "append"; // will append to the file
    public static final String PREV_FILE_SUFFIX = ".prev"; // will be appended to the filename for previous data feed

    // Log levels used by util/Log.java, may replace with log4j at some point...
    public static final int    LOG_DEBUG = 1;
    public static final int    LOG_INFO = 2;
    public static final int    LOG_WARN = 3;
    public static final int    LOG_FATAL = 4;
    public static final int    LOG_OFF = 5;
    
    // *********************************************************************************
    // Function to return the same constants as required in served HTML/JAVASCRIPT pages
    public static String js()
    {
        return "{ \n" +
                  "ZONE_COMPLETION: '"+ZONE_COMPLETION+"',\n" +
                  "ZONE_UPDATE: '"+ZONE_UPDATE+"',\n" +
                  "ZONE_INFO: '"+ZONE_INFO+"',\n" +
                  "SOCKET_ZONE_CONNECT: '"+SOCKET_ZONE_CONNECT+"',\n" +
                  "SOCKET_ZONE_MAP_CONNECT: '"+SOCKET_ZONE_MAP_CONNECT+"',\n" +
                  "SOCKET_FEED_CONNECT: '"+SOCKET_FEED_CONNECT+"'\n" +
            "}";
    }
}
