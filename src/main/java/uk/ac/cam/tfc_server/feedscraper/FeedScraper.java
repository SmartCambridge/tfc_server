package uk.ac.cam.tfc_server.feedscraper;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// FeedScraper.java
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' next-generation Realtime Intelligent Traffic Analysis system
//
// Polls external websites and creates new feeds based on that data.
//
// FeedScraper will WRITE the raw binary post data into:
//   TFC_DATA_MONITOR/<filename>
//   TFC_DATA_BIN/YYYY/MM/DD/<filename>
//
// where <filename> = <UTC TIMESTAMP>_YYYY-MM-DD-hh-mm-ss.bin
// and any prior '.bin' files in TFC_DATA_MONITOR will be deleted
//
// Config values are read from provided vertx config() json file, e.g. see README.md
//
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

    private final String VERSION = "0.32";
    
    // from config()
    private String MODULE_NAME;       // config module.name - normally "feedscraper"
    private String MODULE_ID;         // config module.id
    private String EB_SYSTEM_STATUS;  // config eb.system_status
    private String EB_MANAGER;        // config eb.manager
    
    // scraper config:
    private JsonArray START_FEEDS; // config module_name.feeds parameters
    
    public int LOG_LEVEL; // optional in config(), defaults to Constants.LOG_INFO

    // local constants
    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 25;
    private final int SYSTEM_STATUS_RED_SECONDS = 35;

    // global vars
    private HashMap<String,HttpClient> http_clients; // used to store a HttpClient for each feed_id
    private EventBus eb = null;

    private Log logger;
    
  @Override
  public void start(Future<Void> fut) throws Exception {

    // create holder for HttpClients
    http_clients = new HashMap<String,HttpClient>();

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

    // send periodic "system_status" messages
    vertx.setPeriodic(SYSTEM_STATUS_PERIOD, id -> { send_status();  });

    // iterate through all the feedscrapers to be started
    for (int i=0; i<START_FEEDS.size(); i++)
        {
          start_scraper(START_FEEDS.getJsonObject(i));
        }

  } // end start()

    // start a scraper with a given config
    private void start_scraper(JsonObject config)
    {
          ParseCamParkingLocal parser = new ParseCamParkingLocal(config.getString("area_id"));

          // create monitor directory if necessary
          FileSystem fs = vertx.fileSystem();          
          String monitor_path = config.getString("data_monitor");
          if (!fs.existsBlocking(monitor_path))
          {
            try {
                fs.mkdirsBlocking(monitor_path);
                logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+
                                        ": start_scraper created monitor path "+monitor_path);
            } catch (Exception e) {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                                        ": start_scraper FAIL: error creating monitor path "+monitor_path);
                return;
            }
          }

          logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+
                     ": starting FeedScraper for "+config.getString("host")+config.getString("uri"));

          // call immediately, then every 'period' seconds
          get_feed(config, parser);

          // set up periodic 'GET' requests for data (.setPeriodic requires milliseconds)
          vertx.setPeriodic( config.getInteger("period") * 1000,
                             id -> { get_feed(config, parser);
                           });
    }

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

    private void get_feed(JsonObject config, ParseCamParkingLocal parser)
    {
        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                               ": get_feed "+config.getString("host")+config.getString("uri"));

        // Do a GET to the config feed hostname/uri
        http_clients.get(config.getString("feed_id"))
           .getNow(config.getString("uri"), new Handler<HttpClientResponse>() {

            // this handler called when GET response is received
            @Override
            public void handle(HttpClientResponse client_response) {
                // specify this 'bodyHandler' to handle the entire body of the response (as opposed to parts
                // as they arrive).
                client_response.bodyHandler(new Handler<Buffer>() {
                    // and here we go... handle() will be called with the GET response buffer
                    @Override
                    public void handle(Buffer buffer) {
                        // print out the received GET data for LOG_LEVEL=1 (debug)
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                   ": GET reponse length=" + buffer.length() );


                        // Now send the buffer to be processed, which may cause exception if bad data
                        try {
                          process_feed(buffer, config, parser);
                        }
                        catch (Exception e) {
                            logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+": proceed_feed error");
                            logger.log(Constants.LOG_WARN, e.getMessage());
                        }
                    }
                });
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
  private void process_feed(Buffer buf, JsonObject config, ParseCamParkingLocal parser) throws Exception 
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
    
    // Write file to DATA_BIN
    //
    final String bin_path = config.getString("data_bin")+"/"+filepath;
    final String file_suffix = config.getString("file_suffix");
    write_bin_file(buf, bin_path, filename, file_suffix);

    // Write file to DATA_MONITOR
    //
    final String monitor_path = config.getString("data_monitor");
    write_monitor_file(buf, monitor_path, filename, file_suffix);

    // Parse the received data into a suitable EventBus JsonObject message
    JsonObject msg = new JsonObject();

    msg.put("module_name", MODULE_NAME);
    msg.put("module_id", MODULE_ID);
    msg.put("msg_type", Constants.FEED_CAR_PARKS);
    msg.put("feed_id", config.getString("feed_id"));
    msg.put("filename", filename);
    msg.put("filepath", filepath);
    msg.put("ts", Integer.parseInt(utc_ts));
            
    JsonArray request_data = parser.parse_array(buf.toString());

    msg.put("request_data", request_data);
    
    // debug print out the JsonObject message
    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+": prepared EventBus msg:");
    logger.log(Constants.LOG_DEBUG, msg.toString());

    String feedscraper_address = config.getString("address");

    eb.publish(feedscraper_address, msg);
    
    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
               ": published latest GET data to "+feedscraper_address);
    
  } // end process_feed()

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
                                          Log.log_err("FeedScraper."+MODULE_ID+
                                                      ": error tfc_data_monitor delete: "+f);
                                        }
                                });
                        }
                    write_file(fs, buf, monitor_path+"/"+filename+ file_suffix);
                }
            else
                {
                    Log.log_err(MODULE_NAME+"."+MODULE_ID+
                                ": error reading data_monitor path: "+
                                monitor_path);
                    Log.log_err(monitor_result.cause().getMessage());
                }
        });
    }

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

    // validate_feeds() will validate a FeedScraper feeds config, and insert default values
    // The config is kept as a JsonArray
    private boolean validate_feeds()
    {
        if (START_FEEDS.size() < 1)
            {
                Log.log_err(MODULE_NAME+"."+MODULE_ID+": no "+MODULE_NAME+".feeds in config");
                return false;
            }
        for (int i=0; i<START_FEEDS.size(); i++)
            {
                JsonObject config = START_FEEDS.getJsonObject(i);

                // feed_id is unique for this feed
                if (config.getString("feed_id")==null)
                    {
                        Log.log_err(MODULE_NAME+"."+MODULE_ID+": feed_id missing in config");
                        return false;
                    }

                // ssl yes/no for http request
                if (config.getBoolean("ssl")==null)
                    {
                        config.put("ssl", false);
                    }

                // http port to be used to request the data
                if (config.getInteger("port")==null)
                    {
                        config.put("port", 80);
                    }

                // period (in seconds) between successive 'get' requests for data
                if (config.getInteger("period")==null)
                    {
                        Log.log_err(MODULE_NAME+"."+MODULE_ID+": period missing in config");
                        return false;
                    }

                if (config.getString("data_bin")==null)
                    {
                        Log.log_err(MODULE_NAME+"."+MODULE_ID+": data_bin missing in config");
                        return false;
                    }

                // filesystem path to store the latest 'post_data.bin' file so 
                // it can be monitored for inotifywait processing
                if (config.getString("data_monitor")==null)
                    {
                        Log.log_err(MODULE_NAME+"."+MODULE_ID+": data_monitor missing in config");
                        return false;
                    }

                if (config.getString("file_suffix")==null)
                    {
                        config.put("file_suffix",".bin");
                    }

                // create a new HttpClient for this feed, and add to http_clients list
                http_clients.put(config.getString("feed_id"),
                             vertx.createHttpClient( new HttpClientOptions()
                                                       .setSsl(config.getBoolean("ssl"))
                                                       .setTrustAll(true)
                                                       .setDefaultPort(config.getInteger("port"))
                                                       .setDefaultHost(config.getString("host"))
                            ));

            }

        return true; // if we got to here then we can return ok, error would have exitted earlier
    }        

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


        START_FEEDS = config().getJsonArray(MODULE_NAME+".feeds");
        
        if (!validate_feeds())
            {
                Log.log_err(MODULE_NAME+"."+MODULE_ID+": feeds config() not valid");
                return false;
            }
                
        return true;
    }

} // end FeedScraper class
