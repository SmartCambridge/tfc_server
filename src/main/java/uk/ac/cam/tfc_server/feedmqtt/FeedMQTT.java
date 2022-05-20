package uk.ac.cam.tfc_server.feedmqtt;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// FeedMQTT.java
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' Adaptive City Platform
//
// Receives data from a remote MQTT server.
//
// FeedMQTT will WRITE the raw binary post data into:
//   {{feed_config.data_monitor}}/<filename>
//   {{feed_config.data_bin}//YYYY/MM/DD/<filename>
//
// where <filename> = <UTC MILLISECOND TIMESTAMP>_YYYY-MM-DD-hh-mm-ss.bin
// and any prior '.bin' files in TFC_DATA_MONITOR will be deleted
//
// Config values are read from provided vertx config() json file, e.g. see README.md
//
// FeedMQTT will publish the feed data as a JSON string on eventbus (feed_config.address).
//
// * this verticle is a derivative of the FeedMaker verticle.
//
// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.Handler;

import io.vertx.core.file.FileSystem;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

import io.vertx.mqtt.MqttClient;
import io.vertx.mqtt.MqttClientOptions;
import io.vertx.mqtt.messages.MqttPublishMessage;

import java.io.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.ArrayList;

// other tfc_server classes
import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;

public class FeedMQTT extends AbstractVerticle {

    private final String VERSION = "0.03";
    
    // from config()
    private String MODULE_NAME;       // config module.name - normally "feedscraper"
    private String MODULE_ID;         // config module.id
    private String EB_SYSTEM_STATUS;  // config eb.system_status
    private String EB_MANAGER;        // config eb.manager
    
    // maker configs:
    private JsonArray START_FEEDS; // config module_name.feeds parameters
    
    public int LOG_LEVEL; // optional in config(), defaults to Constants.LOG_INFO

    private int HTTP_PORT;            // config feedmaker.http.port

    // local constants
    private final int SYSTEM_STATUS_PERIOD = 10000; // status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 25;
    private final int SYSTEM_STATUS_RED_SECONDS = 35;
    private final int SYSTEM_WATCHDOG_PERIOD = 15000; // check connect @ 15 seconds

    // global vars
    private HashMap<String,MqttFeed> mqtt_feeds; // store an MqttClient per feed_id
    private EventBus eb = null;

    private Log logger;
    
    @Override
    public void start() throws Exception {

        // create holder for MqttClients, indexed on feed_id
        mqtt_feeds = new HashMap<String,MqttFeed>();

        // load FeedMaker initialization values from config()
        if (!get_config())
              {
                  Log.log_err("FeedMQTT: "+ MODULE_ID + " failed to load initial config()");
                  vertx.close();
                  return;
              }

        logger = new Log(LOG_LEVEL);
        
        logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": Version "+VERSION+" started");

        // create link to EventBus
        eb = vertx.eventBus();

        // send periodic "system_status" messages
        vertx.setPeriodic(SYSTEM_STATUS_PERIOD, id -> { send_status();  });

        // iterate through all the feedmqtt's to be started
        for (int i=0; i<START_FEEDS.size(); i++)
            {
              start_client(START_FEEDS.getJsonObject(i));
            }

    } // end start()


    // *******************************************************************
    // *****  FeedMQTT config can contain multiple feed configs
    // *****  so start each one (with add_feed_handler(feed config json )
    // *******************************************************************
    private void start_client(JsonObject config)
    {
        // ********************************************************************
        // create monitor directory if necessary
        // ********************************************************************
        FileSystem fs = vertx.fileSystem();          
        String monitor_path = config.getString("data_monitor");
        if (!fs.existsBlocking(monitor_path))
        {
          try {
              fs.mkdirsBlocking(monitor_path);
              logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+
                                        ": start_client created monitor path "+monitor_path);
          } catch (Exception e) {
              logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                                        ": start_client FAIL: error creating monitor path "+monitor_path);
              return;
          }
        }
          // monitor_path now exists

        // ************************************************************************************
        // Create MQTT client subscriber as per feed config
        // ************************************************************************************
        logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+"."+
                   config.getString("feed_id")+": starting mqtt feed handler");

        add_feed_handler(config);

    }

    // ******************************
    // send UP status to the EventBus
    private void send_status()
    {
      eb.publish(EB_SYSTEM_STATUS,
                 "{ \"module_name\": \""+MODULE_NAME+"\"," +
                   "\"module_id\": \""+MODULE_ID+"\"," +
                   "\"status\": \"UP\"," +
                   "\"status_msg\": \"UP\"," +
                   "\"status_amber_seconds\": "+String.valueOf( SYSTEM_STATUS_AMBER_SECONDS ) + "," +
                   "\"status_red_seconds\": "+String.valueOf( SYSTEM_STATUS_RED_SECONDS ) +
                 "}" );
    }

    // ************************************************
    // ************************************************
    // Here is where the essential feed MQTT data is handled
    // ************************************************
    // ************************************************
    private void add_feed_handler(JsonObject config)
    {
        //{ 
        // "feed_id" :   "csn",
        // "feed_type":  "feed_mqtt",
        // "host":       "eu.thethings.network",
        // "port":       1883,
        // "topic":      "+/devices/+/up",
        // "username":   "csn",
        // "password":   "ttn-account-v2.HMw7xpOqwertyWv5hrx_7oqvdP4Muiop4tv1Mt",

        // "file_suffix":   ".json",
        // "data_bin" :     "/media/tfc/csn_ttn/data_bin",
        // "data_monitor" : "/media/tfc/csn_ttn/data_monitor",

        // "msg_type" :  "feed_mqtt",
        // "address" :   "tfc.feedmqtt.dev"
        //}
        
        MqttFeed feed = new MqttFeed(config);

        if (feed.client == null)
        {
            logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+"."+feed.FEED_ID+
                       ": Bad MQTT feed config" );
            return;
        }

    }

    // ***********************************************    
    // get current local time as "YYYY-MM-DD-hh-mm-ss"
    private String local_datetime_string()
    {
        LocalDateTime local_time = LocalDateTime.now();
        return local_time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"));
    }

  // *****************************************************************
  // process the received raw data
  private void process_feed(MqttPublishMessage mqtt_msg, JsonObject config) throws Exception 
  {

    // Extract the actual MQTT message data (as Buffer)
    Buffer buf = mqtt_msg.payload();

    LocalDateTime local_time = LocalDateTime.now();
    
    String day = local_time.format(DateTimeFormatter.ofPattern("dd"));
    String month = local_time.format(DateTimeFormatter.ofPattern("MM"));
    String year = local_time.format(DateTimeFormatter.ofPattern("yyyy"));

    Instant now = Instant.now();
    long utc_milliseconds = now.toEpochMilli();

    // The object sent i the messagebus will include "ts": utc_seconds
    long utc_seconds = utc_milliseconds / 1000;

    // Built utc_ts as "<UTC Seconds>.<UTC Milliseconds>" for use in the filename
    String utc_milli_string = String.valueOf(utc_milliseconds);  // ~UTC time in milliseconds
    int utc_len = utc_milli_string.length();
    String utc_ts = utc_milli_string.substring(0,utc_len-3)+"."+utc_milli_string.substring(utc_len-3,utc_len);

    // A possible alternative will be to use an ISO 8601 UTC string for the timestamp
    //String utc_datetime = now.toString();

    // filename without the suffix
    String filename = utc_ts+"_"+local_time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"));
    // sub-dir structure to store the file
    String filepath = year+"/"+month+"/"+day;
    
    // Write file to DATA_BIN
    //
    final String bin_path = config.getString("data_bin")+"/"+filepath;
    final String file_suffix = config.getString("file_suffix");
    write_bin_file(buf, bin_path, filename, file_suffix);

    // Write file to DATA_MONITOR
    //
    final String monitor_path = config.getString("data_monitor");
    write_monitor_file(buf, monitor_path, filename, file_suffix);

    // ********************************************************************************************
    // Finally, here is where we PARSE the incoming data and put it in the 'request_data' property
    // ********************************************************************************************

    try {            

        JsonObject msg = new JsonObject();

        // Parse the received data into a suitable EventBus JsonObject message

        // The actual MQTT data will be the single element of the eventbus message "request_data" property
        JsonObject mqtt_data = new JsonObject(buf);

        JsonArray request_data = new JsonArray();

        request_data.add(mqtt_data);

        msg.put("request_data", request_data);

        msg.put("module_name", MODULE_NAME);
        msg.put("module_id", MODULE_ID);
        msg.put("feed_id", config.getString("feed_id"));
        msg.put("filename", filename);
        msg.put("filepath", filepath);
        msg.put("ts", utc_seconds);

        msg.put("msg_type", config.getString("msg_type"));
  
        // debug print out the JsonObject message
        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+": prepared EventBus msg:");
        logger.log(Constants.LOG_DEBUG, msg.toString());

        String eventbus_address = config.getString("address");

        eb.publish(eventbus_address, msg);
    
        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                   ": published latest MQTT feed data to "+eventbus_address);
    }
    catch (Exception e) {
        logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                   ": exception raised during parsing of feed "+config.getString("feed_id")+":");
        logger.log(Constants.LOG_WARN, e.getMessage());
    }
  } // end process_feed()

    // ******************************************************************
    // write_bin_file()
    //
    // Write the 'buf' (i.e. the binary data as received) into a file at
    // 'bin_path/filename/file_suffix'
    //
    private void write_bin_file(Buffer buf, String bin_path, String filename, String file_suffix)
    {
        FileSystem fs = vertx.fileSystem();
        // if full directory path exists, then write file
        // otherwise create full path first
    
        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                   ": Writing "+bin_path+"/"+filename + file_suffix);
        fs.exists(bin_path, result -> {
                if (result.succeeded() && result.result())
                    {
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                   ": process_feed: path "+bin_path+" exists");
                        write_file(fs, buf, bin_path+"/"+filename+ file_suffix);
                    }
                else
                    {
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                   ": Creating directory "+bin_path);
                        fs.mkdirs(bin_path, mkdirs_result -> {
                                if (mkdirs_result.succeeded())
                                    {
                                        write_file(fs, buf, bin_path+"/"+filename+ file_suffix);
                                    }
                                else
                                    {
                                        Log.log_err(MODULE_NAME+"."+MODULE_ID+
                                                    ": error creating tfc_data_bin path "+bin_path);
                                    }
                            });
                    }
        });
    }        

    // ************************************************************************************
    // write_monitor_file()
    //
    // Write 'buf' to file in the filesystem, deleting previous files in the same directory
    // This is convenient for a separate 'inotifywait' process to listen for file-close-write
    // events on that directory and trigger separate processes, e.g. to POST the file onward.
    //
    private void write_monitor_file(Buffer buf, String monitor_path, String filename, String file_suffix)
    {
        FileSystem fs = vertx.fileSystem();

        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
               ": Writing "+monitor_path+"/"+filename+ file_suffix);
        fs.readDir(monitor_path, ".*\\"+file_suffix, monitor_result -> {
            if (monitor_result.succeeded())
                {
                    // directory exists, delete previous files of same suffix
                    for (String f: monitor_result.result())
                        {
                            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                       ": Deleting "+f);
                            fs.delete(f, delete_result -> {
                                    if (!delete_result.succeeded())
                                        {
                                          logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                                                      ": error tfc_data_monitor delete: "+f);
                                        }
                                });
                        }
                    write_file(fs, buf, monitor_path+"/"+filename+ file_suffix);
                }
            else
                {
                    logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                                ": error reading data_monitor path: "+
                                monitor_path);
                    logger.log(Constants.LOG_WARN, monitor_result.cause().getMessage());
                }
        });
    }

    // ***************************************************
    // Write the 'buf' as a file 'filepath' (non-blocking)
    private void write_file(FileSystem fs, Buffer buf, String file_path)
    {
        fs.writeFile(file_path, 
                     buf, 
                     result -> {
            if (result.succeeded()) {
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                             ": File "+file_path+" written");
            } else {
                Log.log_err(MODULE_NAME+"."+MODULE_ID+": write_file error ..." + result.cause());
            }
        });
    } // end write_file

    // Load initialization global constants defining this FeedMaker from config()
    private boolean get_config()
    {
        // config() values needed by all TFC modules are:
        //   module.name - usually "zone"
        //   module.id - unique module reference to be used by this verticle
        //   eb.system_status - String eventbus address for system status messages
        //   eb.manager - eventbus address for manager messages
        
        MODULE_NAME = config().getString("module.name");
        if (MODULE_NAME == null)
            {
                Log.log_err("FeedMaker: config() not set");
                return false;
            }

        MODULE_ID = config().getString("module.id");
        if (MODULE_ID == null)
            {
                Log.log_err(MODULE_NAME+": module.id config() not set");
                return false;
            }

        LOG_LEVEL = config().getInteger(MODULE_NAME+".log_level", 0);
        if (LOG_LEVEL==0)
            {
                LOG_LEVEL = Constants.LOG_INFO;
            }
        
        EB_SYSTEM_STATUS = config().getString("eb.system_status");
        if (EB_SYSTEM_STATUS == null)
            {
                Log.log_err(MODULE_NAME+"."+MODULE_ID+": eb.system_status config() not set");
                return false;
            }

        EB_MANAGER = config().getString("eb.manager");
        if (EB_MANAGER == null)
            {
                Log.log_err(MODULE_NAME+"."+MODULE_ID+": eb.manager config() not set");
                return false;
            }

        START_FEEDS = config().getJsonArray(MODULE_NAME+".feeds");
        
        return true;
    }

    // **********************************************************
    // ****  CLASS MqttFeed
    // **********************************************************
    private class MqttFeed {

        JsonObject config; // JSON config object given in instantiation
        String FEED_ID;    // which is checked and parsed into these values
        String USERNAME;
        String PASSWORD;
        Integer PORT;
        String HOST;
        String TOPIC;

        MqttClient client = null; // MqttFeed object will be null if config bad

        boolean watchdog_running = false; // set 'true' to avoid multiple instances

        long watchdog_timer; // id of timer - so we can reset to longer interval

        long watchdog_count = 0;

        int watchdog_period = SYSTEM_WATCHDOG_PERIOD; // time (s) between status checks

        int WATCHDOG_MAX = 10; // when disconnected, check WATCHDOG_MAX times before doubling period

        MqttFeed(JsonObject config) {

            this.config = config;

            MqttClientOptions client_options = new MqttClientOptions();

            USERNAME = config.getString("username");

            if (USERNAME != null)
            {
                client_options.setUsername(USERNAME);
            }

            PASSWORD = config.getString("password");

            if (PASSWORD != null)
            {
                client_options.setPassword(PASSWORD);
            }

            FEED_ID = config.getString("feed_id");

            if (FEED_ID == null)
            {
                FEED_ID = "<no feed_id in config>";

                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+".?"+
                           ": Bad 'feed_id' entry in feedmqtt config" );
                return;
            }

            PORT = config.getInteger("port");

            if (PORT == null)
            {
                PORT = 1883; // default port for MQTT
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+
                           ": No 'port' entry in feedmqtt config "+FEED_ID+ " using 1883" );
            }

            HOST = config.getString("host");

            if (HOST == null)
            {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+
                           ": Bad 'host' entry in feedmqtt config "+FEED_ID );
                return;
            }

            TOPIC = config.getString("topic");

            if (TOPIC == null)
            {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+
                           ": Bad 'topic' entry in feedmqtt config "+FEED_ID );
                return;
            }

            // Initialize and connect the MQTT client
            init(client_options);
        }

        private void init(MqttClientOptions client_options)
        {
            // *********************************
            // NOW CREATE THE VERTX MQTT CLIENT
            // *********************************
            client = MqttClient.create(vertx, client_options);

            // catch exceptions buried in netty
            client.exceptionHandler( e -> {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+
                           ": MQTT exception" );
            });

            // ***************************************************
            // REGISTER MQTT CLOSE HANDLER 
            // ***************************************************
            client.closeHandler( Void -> {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+
                           ": MQTT server closed connection" );

            }); // end closeHandler

            // ***************************************************
            // REGISTER MQTT SUBSCRIBE CALLBACK
            // ***************************************************
            client.publishHandler( mqtt_data -> {
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+": MQTT data received");
                try {
                    process_feed(mqtt_data, config);
                }
                catch (Exception e)
                {
                    logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+
                               ": MQTT process_feed exception");
                    logger.log(Constants.LOG_WARN, e.getMessage());
                    return;
                }
            }); // end publishHandler

            // ***************************************************
            // CONNECT TO MQTT SERVER
            // ***************************************************
            // 
            //connect();
            //
            // Starting the watchdog
            // This will automatically connect the first time if we're not connected
            //
            start_watchdog(SYSTEM_WATCHDOG_PERIOD); // if watchdog already running, this will do nothing


            logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+": MQTT handler started");
        }

        // ***************************************************
        // CONNECT TO MQTT SERVER
        // ***************************************************
        private void connect()
        {
            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+
                       ": MQTT connecting to "+HOST+":"+PORT.toString());

            // Connect to the MQTT server
            // Note we will only start the watchdog AFTER the first connect attempt, good or bad, to avoid race.
            client.connect(PORT, HOST, connect_response -> {
                if (connect_response.succeeded())
                {
                    logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+": MQTT connected");

                    // subscribe to the MQTT topic, e.g. '+/devices/+/up' for TTN device uplink data
                    subscribe();
                }
                else
                {
                    logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+": MQTT connect FAILED");
                }
            }); // end connect
        }

        // *************************
        // SUBSCRIBE TO MQTT TOPIC
        // *************************
        private void subscribe()
        {
             logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+": MQTT subscribing");

             client.subscribe(TOPIC, 0); // Subscribe with QoS ZERO
        }

        // *************************
        // START WATCHDOG TIMER
        // *************************
        private void start_watchdog(int period)
        {
            logger.log(Constants.LOG_INFO,MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+
                    ": MQTT start_watchdog period="+Integer.toString(period)+
                    ", running="+watchdog_running);

            if (!watchdog_running)
            {
                watchdog_running = true;
                    
                logger.log(Constants.LOG_INFO,MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+": MQTT starting watchdog");

                // send periodic "system_status" messages
                watchdog_timer = vertx.setPeriodic(period, id -> { watchdog();  });
            }
        }

        // *************************
        // WATCHDOG CHECKS
        // wakes up on timer
        // *************************
        private void watchdog()
        {
            logger.log(Constants.LOG_DEBUG,MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+
                    ": MQTT watchdog period="+Integer.toString(watchdog_period)+
                    ", "+(client.isConnected() ? "connected" : "DISCONNECTED"));

            if (!client.isConnected())
            {

                logger.log(Constants.LOG_DEBUG,MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+
                    ": MQTT watchdog calling connect()");

                // re-connect
                connect();
            }
        }

    } // end MqttFeed class

} // end FeedMQTT class

