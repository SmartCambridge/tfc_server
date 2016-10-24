package uk.ac.cam.tfc_server.feedscraper;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// FeedScraper.java
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' next-generation Realtime Intelligent Traffic Analysis system
//
// Polls external websites and creates new feeds based on that data
//
// Data is currently received as a POST to <MODULE_NAME>/<MODULE_ID>
// every 30 seconds for approx 1200 vehicles
//
// FeedScraper will WRITE the raw binary post data into:
//   TFC_DATA_MONITOR/<filename>
//   TFC_DATA_BIN/YYYY/MM/DD/<filename>
//
// where <filename> = <UTC TIMESTAMP>_YYYY-MM-DD-hh-mm-ss.bin
// and any prior '.bin' files in TFC_DATA_MONITOR will be deleted
//
// Config values are read from provided vertx config() json file, e.g.
/*
{
    "main":    "uk.ac.cam.tfc_server.feedscraper.FeedScraper",
    "options":
        { "config":
                {

                    "module.name":           "feedscraper",
                    "module.id":             "cam_park_and_ride",

                    "eb.system_status":      "tfc.system_status",
                    "eb.console_out":        "tfc.console_out",
                    "eb.manager":            "tfc.manager",

                    "feedscraper.address" :   "tfc.feedscraper.cam_park_and_ride",

                    "feedscraper.tfc_data_bin":     "/media/tfc/cam/data_bin_cam_park_and_ride",
                    "feedscraper.tfc_data_monitor": "/media/tfc/cam/data_monitor_cam_park_and_ride"
                }
        }
}
*/
// FeedScraper will publish the feed data as a JSON string on eventbus.
//
// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.file.FileSystem;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

import java.io.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.ArrayList;

// other tfc_server classes
import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;

public class FeedScraper extends AbstractVerticle {

    private final String VERSION = "0.11";
    
    // from config()
    private String MODULE_NAME;       // config module.name - normally "feedscraper"
    private String MODULE_ID;         // config module.id
    private String EB_SYSTEM_STATUS;  // config eb.system_status
    private String EB_MANAGER;        // config eb.manager
    
    // scraper config:
    private ArrayList<FeedConfig> START_FEEDS; // config module_name.feeds parameters
    
    public int LOG_LEVEL; // optional in config(), defaults to Constants.LOG_INFO

    // local constants
    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 25;
    private final int SYSTEM_STATUS_RED_SECONDS = 35;

    // global vars
    private HttpClient http_client = null;
    private EventBus eb = null;

    private Log logger;
    
    
  @Override
  public void start(Future<Void> fut) throws Exception {

    boolean ok = true; // simple boolean to flag an abort during startup

    // load FeedScraper initialization values from config()
    if (!get_config())
          {
              Log.log_err("FeedScraper: "+ MODULE_ID + " failed to load initial config()");
              vertx.close();
              return;
          }

    logger = new Log(LOG_LEVEL);
    
    logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": Version "+VERSION+" started");

    // create link to EventBus
    eb = vertx.eventBus();

    // create webserver
    http_client = vertx.createHttpClient();

    // send periodic "system_status" messages
    vertx.setPeriodic(SYSTEM_STATUS_PERIOD, id -> { send_status();  });

    // iterate through all the filers to be started
    for (int i=0; i<START_FEEDS.size(); i++)
        {
          FeedConfig config = START_FEEDS.get(i);
          logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+
                     ": starting FeedScraper for "+config.host+config.uri);

          // set up periodic 'GET' requests for data (.setPeriodic requires milliseconds)
          vertx.setPeriodic( config.period * 1000, id -> { get_feed(config);  });
        }

  } // end start()

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

    private void get_feed(FeedConfig config)
    {
        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                               ": get_feed "+config.host+config.uri);

        config.http_client.getNow(config.uri, new Handler<HttpClientResponse>() {

            @Override
            public void handle(HttpClientResponse client_response) {
                client_response.bodyHandler(new Handler<Buffer>() {
                    @Override
                    public void handle(Buffer buffer) {
                        System.out.println("Response (" + buffer.length() + "): ");
                        System.out.println(buffer.getString(0, buffer.length()));
                        }
                    });
                System.out.println("Response received");
            }
        });
    }
    
    // get current local time as "YYYY-MM-DD-hh-mm-ss"
  private String local_datetime_string()
    {
        LocalDateTime local_time = LocalDateTime.now();
        return local_time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"));
    }

  // process the received raw data
  private void process_feed(Buffer buf, FeedConfig config) throws Exception 
  {

    LocalDateTime local_time = LocalDateTime.now();
    
    String day = local_time.format(DateTimeFormatter.ofPattern("dd"));
    String month = local_time.format(DateTimeFormatter.ofPattern("MM"));
    String year = local_time.format(DateTimeFormatter.ofPattern("yyyy"));
    String utc_ts = String.valueOf(System.currentTimeMillis() / 1000);

    // filename without the suffix
    String filename = utc_ts+"_"+local_time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"));
    // sub-dir structure to store the file
    String filepath = year+"/"+month+"/"+day;
    // First just save the binary file to $TFC_DATA_MONITOR

    // Vert.x non-blocking file write...
    FileSystem fs = vertx.fileSystem();

    // Write file to DATA_BIN
    //
    // if full directory path exists, then write file
    // otherwise create full path first
    final String bin_path = config.data_bin+"/"+filepath;
    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
               ": Writing "+bin_path+"/"+filename + config.file_suffix);
    fs.exists(bin_path, result -> {
            if (result.succeeded() && result.result())
                {
                    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                               ": process_feed: path "+bin_path+" exists");
                    write_file(fs, buf, bin_path+"/"+filename+ config.file_suffix);
                }
            else
                {
                    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                               ": Creating directory "+bin_path);
                    fs.mkdirs(bin_path, mkdirs_result -> {
                            if (mkdirs_result.succeeded())
                                {
                                    write_file(fs, buf, bin_path+"/"+filename+ config.file_suffix);
                                }
                            else
                                {
                                    Log.log_err("FeedScraper."+MODULE_ID+": error creating tfc_data_bin path "+bin_path);
                                }
                        });
                }
        });

    // Write file to DATA_MONITOR
    //
    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
               ": Writing "+config.data_monitor+"/"+filename+ config.file_suffix);
    fs.readDir(config.data_monitor, ".*\\"+config.file_suffix, monitor_result -> {
                            if (monitor_result.succeeded())
                                {
                                    // directory exists, delete previous files of same suffix
                                    for (String f: monitor_result.result())
                                        {
                                            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+"Deleting "+f);
                                            fs.delete(f, delete_result -> {
                                                    if (!delete_result.succeeded())
                                                        {
                                                          Log.log_err("FeedScraper."+MODULE_ID+": error tfc_data_monitor delete: "+f);
                                                        }
                                                });
                                        }
                                    write_file(fs, buf, config.data_monitor+"/"+filename+ config.file_suffix);
                                }
                            else
                                {
                                    Log.log_err("FeedScraper."+MODULE_ID+": error reading tfc_data_monitor path: "+config.data_monitor);
                                    Log.log_err(monitor_result.cause().getMessage());
                                }
    });

    // Here is where we process the individual position records
    //JsonObject msg = GTFS.buf_to_json(buf, filename, filepath);

    //msg.put("module_name", MODULE_NAME);
    //msg.put("module_id", MODULE_ID);
    //msg.put("msg_type", Constants.FEED_BUS_POSITION);

    //eb.publish(FEEDSCRAPER_ADDRESS, msg);
    
    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
               ": published latest GET data");
    
  } // end process_gtfs()

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

    // Load initialization global constants defining this FeedScraper from config()
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
                Log.log_err("FeedScraper: config() not set");
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

        START_FEEDS = new ArrayList<FeedConfig>();
        JsonArray config_feed_list = config().getJsonArray(MODULE_NAME+".feeds");
        for (int i=0; i<config_feed_list.size(); i++)
            {
                JsonObject config_json = config_feed_list.getJsonObject(i);

                // add MODULE_NAME, MODULE_ID to every FeedConfig
                config_json.put("module_name", MODULE_NAME);
                config_json.put("module_id", MODULE_ID);
                
                FeedConfig feed_config = new FeedConfig(config_json);
                
                if (feed_config.valid)
                {
                    START_FEEDS.add(feed_config);
                }
                else
                {
                   Log.log_err(MODULE_NAME+"."+MODULE_ID+": FeedConfig skipped "+feed_config.host+feed_config.uri);
                }
            }

        return true;
    }

    class FeedConfig {
        public int period;
        public String host = null;
        public String uri = null;
        public boolean ssl;
        public int port;

        public String address = null;
        public String data_bin = null;
        public String data_monitor = null;
        public String file_suffix = null;

        public HttpClient http_client;

        public boolean valid;

        FeedConfig(JsonObject config)
        {
            // eventbus address this FeedScraper will broadcast onto
            address = config.getString("address");

            // web address this FeedScraper will poll for data
            host = config.getString("host");
            uri = config.getString("uri");

            ssl = config.getBoolean("ssl",false);

            port = config.getInteger("port", 80);

            // period (in seconds) between successive 'get' requests for data
            period = config.getInteger("period", 100);

            // primary filesystem path to store the data exactly as received (i.e. GTFS binary .bin files)
            data_bin = config.getString("data_bin");

            // filesystem path to store the latest 'post_data.bin' file so it can be monitored for inotifywait processing
            data_monitor = config.getString("data_monitor");
        
            // filename suffix for file, default '.bin'
            file_suffix = config.getString("file_suffix");

            http_client = vertx.createHttpClient( new HttpClientOptions()
                                                       .setSsl(ssl)
                                                       .setTrustAll(true)
                                                       .setDefaultPort(port)
                                                       .setDefaultHost(host)
                                                );

            valid = true;
        }        
    }    
} // end FeedScraper class
