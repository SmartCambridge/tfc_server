package uk.ac.cam.tfc_server.feedhandler;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// FeedHandler.java
// Version 0.11
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' next-generation Realtime Intelligent Traffic Analysis system
//
// Provides an HTTP server that receives the vehicle location data
// as Google GTFS-realtime POST data.
//
// Data is currently received as a POST to <MODULE_NAME>/<MODULE_ID>
// every 30 seconds for approx 1200 vehicles
//
// FeedHandler will WRITE the raw binary post data into:
//   TFC_DATA_MONITOR/<filename>
//   TFC_DATA_BIN/YYYY/MM/DD/<filename>
//   TFC_DATA_CACHE/YYYY/MM-DD/<filename>
// where <filename> = <UTC TIMESTAMP>_YYYY-MM-DD-hh-mm-ss.bin
// and any prior '.bin' files in TFC_DATA_MONITOR will be deleted
//
// Config values are read from provided vertx config() json file, e.g.
/*
{
    "main":    "uk.ac.cam.tfc_server.feedhandler.FeedHandler",
    "options":
        { "config":
                {

                    "module.name":           "feedhandler",
                    "module.id":             "A",

                    "eb.system_status":      "tfc.system_status",
                    "eb.console_out":        "tfc.console_out",
                    "eb.manager":            "tfc.manager",

                    "feedhandler.address" :   "tfc.feedhandler.A",
                    "feedhandler.http.port" : 8080,
                    "feedhandler.tfc_data_bin":     "/home/ijl20/tfc_server_data/data_bin",
                    "feedhandler.tfc_data_cache":   "/home/ijl20/tfc_server_data/data_cache",
                    "feedhandler.tfc_data_monitor": "/home/ijl20/tfc_server_data/data_monitor"
                }
        }
}
*/
// FeedHandler will publish the feed data as a JSON string on eventbus.
//
// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.file.FileSystem;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

import java.io.*;
import java.time.*;
import java.time.format.*;
import java.util.*;

// other tfc_server classes
import uk.ac.cam.tfc_server.util.GTFS;
import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;

public class FeedHandler extends AbstractVerticle {

    // from config()
    private String MODULE_NAME;       // config module.name - normally "feedhandler"
    private String MODULE_ID;         // config module.id
    private String EB_SYSTEM_STATUS;  // config eb.system_status
    private String EB_MANAGER;        // config eb.manager
    
    private int HTTP_PORT;            // config feedplayer.http.port

    private String FEEDHANDLER_ADDRESS; // config MODULE_NAME.address
    
    private String TFC_DATA_CACHE = null;   // MODULE_NAME.tfc_data_cache
    private String TFC_DATA_BIN = null;     // MODULE_NAME.tfc_data_bin
    private String TFC_DATA_MONITOR = null; // MODULE_NAME.tfc_data_monitor

    private String FILE_SUFFIX;             // MODULE_NAME.file_suffix, default ".bin"
    
    public int LOG_LEVEL; // optional in config(), defaults to Constants.LOG_INFO

    // local constants
    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 15;
    private final int SYSTEM_STATUS_RED_SECONDS = 25;

    // global vars
    private HttpServer http_server = null;
    private EventBus eb = null;

    private Log logger;
    
    private String BASE_URI; // defined the http POST base for this FeedHandler
    
  @Override
  public void start(Future<Void> fut) throws Exception {

    boolean ok = true; // simple boolean to flag an abort during startup

    // load FeedHandler initialization values from config()
    if (!get_config())
          {
              Log.log_err("FeedHandler: "+ MODULE_ID + " failed to load initial config()");
              vertx.close();
              return;
          }

    logger = new Log(LOG_LEVEL);
    
    logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": started on "+FEEDHANDLER_ADDRESS);

    // set up base URI that will be used for feed post, e.g. feedhandler/vix
    BASE_URI = MODULE_NAME + "/" + MODULE_ID;

    // create link to EventBus
    eb = vertx.eventBus();

    // create webserver
    http_server = vertx.createHttpServer();

    // create request router for webserver
    Router router = Router.router(vertx);

    // create bodyhandler for expected feed posts, and set max post size (in bytes)
    //router.route().handler(BodyHandler.create().setBodyLimit(Constants.FEEDHANDLER_MAX_POST));
    
    // ************************************
    // create handler for GET from BASE_URI
    // ************************************

    router.route(HttpMethod.GET,"/"+BASE_URI).handler( ctx -> {

        HttpServerResponse response = ctx.response();
        response.putHeader("content-type", "text/html");

        response.end("<h1>TFC Rita FeedHandler at "+BASE_URI+"</h1><p>Vertx-Web!</p>");
    });

    // ************************************************
    // ************************************************
    // Here is where the essential feed POST is handled
    // create handler for POST from BASE_URI
    // ************************************************
    // ************************************************

    router.route(HttpMethod.POST,"/"+BASE_URI).handler( ctx -> {
            ctx.request().bodyHandler( body_data -> {
                try {
                    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                               ": header="+ctx.request().getHeader("Authorization"));
                    process_gtfs(body_data);
                }
                catch (Exception ex) {
                  Log.log_err("FeedHandler."+MODULE_ID+": process_gtfs Exception");
                  Log.log_err(ex.getMessage());
                }
                ctx.request().response().end("");
            });

    });

    // ********************************
    // connect router to http_server
    // ********************************

    http_server.requestHandler(router::accept).listen(HTTP_PORT);
    
    // send periodic "system_status" messages
    vertx.setPeriodic(SYSTEM_STATUS_PERIOD, id -> {
      eb.publish(EB_SYSTEM_STATUS,
                 "{ \"module_name\": \""+MODULE_NAME+"\"," +
                   "\"module_id\": \""+MODULE_ID+"\"," +
                   "\"status\": \"UP\"," +
                   "\"status_amber_seconds\": "+String.valueOf( SYSTEM_STATUS_AMBER_SECONDS ) + "," +
                   "\"status_red_seconds\": "+String.valueOf( SYSTEM_STATUS_RED_SECONDS ) +
                 "}" );
      });

  } // end start()

    // get current local time as "YYYY-MM-DD-hh-mm-ss"
  private String local_datetime_string()
    {
        LocalDateTime local_time = LocalDateTime.now();
        return local_time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"));
    }

    // print msg to stderr prepended with local time
  private void log_err(String msg)
    {
        System.err.println(local_datetime_string()+" "+msg);
    }
    
  // process the POST gtfs binary data
  private void process_gtfs(Buffer buf) throws Exception {

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

    // Write file to $TFC_DATA_BIN
    //
    // if full directory path exists, then write file
    // otherwise create full path first
    final String bin_path = TFC_DATA_BIN+"/"+filepath;
    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
               ": Writing "+bin_path+"/"+filename + FILE_SUFFIX);
    fs.exists(bin_path, result -> {
            if (result.succeeded() && result.result())
                {
                    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                               ": process_gtfs: path "+bin_path+" exists");
                    write_file(fs, buf, bin_path+"/"+filename+ FILE_SUFFIX);
                }
            else
                {
                    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                               ": Creating directory "+bin_path);
                    fs.mkdirs(bin_path, mkdirs_result -> {
                            if (mkdirs_result.succeeded())
                                {
                                    write_file(fs, buf, bin_path+"/"+filename+ FILE_SUFFIX);
                                }
                            else
                                {
                                    Log.log_err("FeedHandler."+MODULE_ID+": error creating tfc_data_bin path "+bin_path);
                                }
                        });
                }
        });

    // Write file to $TFC_DATA_CACHE
    //
    final String cache_path = TFC_DATA_CACHE+"/"+filepath;
    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
               ": Writing "+cache_path+"/"+filename+ FILE_SUFFIX);
    // if full directory path exists, then write file
    // otherwise create full path first
    fs.exists(cache_path, result -> {
            if (result.succeeded() && result.result())
                {
                    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                               ": process_gtfs: path "+cache_path+" exists");
                    write_file(fs, buf, cache_path+"/"+filename+ FILE_SUFFIX);
                }
            else
                {
                    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                               ": Creating directory "+cache_path);
                    fs.mkdirs(cache_path, mkdirs_result -> {
                            if (mkdirs_result.succeeded())
                                {
                                    write_file(fs, buf, cache_path+"/"+filename+ FILE_SUFFIX);
                                }
                            else
                                {
                                    Log.log_err("FeedHandler."+MODULE_ID+": error creating tfc_data_cache path "+cache_path);
                                }
                        });
                }
        });

    // Write file to $TFC_DATA_MONITOR
    //
    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
               ": Writing "+TFC_DATA_MONITOR+"/"+filename+ FILE_SUFFIX);
    fs.readDir(TFC_DATA_MONITOR, ".*\\"+FILE_SUFFIX, monitor_result -> {
                            if (monitor_result.succeeded())
                                {
                                    // directory exists, delete previous files of same suffix
                                    for (String f: monitor_result.result())
                                        {
                                            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+"Deleting "+f);
                                            fs.delete(f, delete_result -> {
                                                    if (!delete_result.succeeded())
                                                        {
                                                          Log.log_err("FeedHandler."+MODULE_ID+": error tfc_data_monitor delete: "+f);
                                                        }
                                                });
                                        }
                                    write_file(fs, buf, TFC_DATA_MONITOR+"/"+filename+ FILE_SUFFIX);
                                }
                            else
                                {
                                    Log.log_err("FeedHandler."+MODULE_ID+": error reading tfc_data_monitor path: "+TFC_DATA_MONITOR);
                                    Log.log_err(monitor_result.cause().getMessage());
                                }
    });

    // Here is where we process the individual position records
    JsonObject msg = GTFS.buf_to_json(buf, filename, filepath);

    msg.put("module_name", MODULE_NAME);
    msg.put("module_id", MODULE_ID);
    msg.put("msg_type", Constants.FEED_BUS_POSITION);

    eb.publish(FEEDHANDLER_ADDRESS, msg);
    
    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
               ": FeedHandler published (feed_vehicle, pos_records)");
    
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
        Log.log_err("FeedHandler."+MODULE_ID+": write_file error ..." + result.cause());
      }
    });
  } // end write_file

    // Load initialization global constants defining this FeedHandler from config()
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
                Log.log_err("FeedHandler: config() not set");
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

        // eventbus address this FeedHandler will broadcast onto
        FEEDHANDLER_ADDRESS = config().getString(MODULE_NAME+".address");
        if (FEEDHANDLER_ADDRESS == null)
            {
                Log.log_err(MODULE_NAME+"."+MODULE_ID+": "+MODULE_NAME+".address config() not set");
                return false;
            }

        // web address for this FeedHandler to receive POST data messages from original source
        HTTP_PORT = config().getInteger(MODULE_NAME+".http.port",0);
        if (HTTP_PORT == 0)
        {
          Log.log_err(MODULE_NAME+"."+MODULE_ID+": "+MODULE_NAME+".http_port config() var not set");
          return false;
        }

        // backup alternate filesystem path for use when the 'tfc_data_bin' path fails
        TFC_DATA_CACHE = config().getString(MODULE_NAME+".tfc_data_cache");
        if (TFC_DATA_CACHE == null)
        {
          Log.log_err(MODULE_NAME+"."+MODULE_ID+": "+MODULE_NAME+".tfc_data_cache config() var not set");
          return false;
        }

        // primary filesystem path to store the data exacly as received (i.e. GTFS binary .bin files)
        TFC_DATA_BIN = config().getString(MODULE_NAME+".tfc_data_bin");
        if (TFC_DATA_BIN == null)
        {
          Log.log_err(MODULE_NAME+"."+MODULE_ID+": "+MODULE_NAME+".tfc_data_bin config() var not set");
          return false;
        }

        // filesystem path to store the latest 'post_data.bin' file so it can be monitored for inotifywait processing
        TFC_DATA_MONITOR = config().getString(MODULE_NAME+".tfc_data_monitor");
        if (TFC_DATA_MONITOR == null)
        {
          Log.log_err(MODULE_NAME+"."+MODULE_ID+": "+MODULE_NAME+".tfc_data_monitor config() var not set");
          return false;
        }
        
        // filename suffix for file, default '.bin'
        FILE_SUFFIX = config().getString(MODULE_NAME+".file_suffix");
        if (FILE_SUFFIX == null)
        {
            FILE_SUFFIX = ".bin";
        }
        
        return true;
    }
    
} // end FeedHandler class
