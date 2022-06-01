package uk.ac.cam.tfc_server.feedmaker;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// FeedMaker.java
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' Adaptive City Platform
//
// Receives data from an external source by:
// 1) Polling external websites with http GET and creating new feeds based on that data.
//
// 2) Receive same data via POST on feed config 'http.port:http.uri' or 'http.port:/module_name/module_id/feed_id'
//
//
// FeedMaker will WRITE the raw binary post data into:
//   {{feed_config.data_bin}}/<filename>
//   {{feed_config.data_bin}}//YYYY/MM/DD/<filename>
//
// where <filename> = <UTC MILLISECOND TIMESTAMP>_YYYY-MM-DD-hh-mm-ss.bin
// and any prior '.bin' files in TFC_DATA_MONITOR will be deleted
//
// Config values are read from provided vertx config() json file, e.g. see README.md
//
// FeedMaker will publish the feed data as a JSON string on eventbus (feed_config.address).
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

import io.vertx.ext.web.Router;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;

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

public class FeedMaker extends AbstractVerticle {

    private final String VERSION = "0.60.1";

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
    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 25;
    private final int SYSTEM_STATUS_RED_SECONDS = 35;

    // global vars
    private HashMap<String,WebClient> web_clients; // used to store a WebClient for each feed_id
    private EventBus eb = null;

    private Log logger;

    @Override
    public void start() throws Exception {

    Router router = null;

    String BASE_URI = null;

    // create holder for WebClients
    web_clients = new HashMap<String,WebClient>();

    // load FeedMaker initialization values from config()
    if (!get_config())
          {
              Log.log_err("FeedMaker: "+ MODULE_ID + " failed to load initial config()");
              vertx.close();
              return;
          }

    logger = new Log(LOG_LEVEL);

    logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": Version "+VERSION+" started");

    // create link to EventBus
    eb = vertx.eventBus();

    // send periodic "system_status" messages
    vertx.setPeriodic(SYSTEM_STATUS_PERIOD, id -> { send_status();  });

    // create webserver
    HttpServer http_server = vertx.createHttpServer();

    // create general http GET request router for webserver, for general web page provision.
    if (HTTP_PORT != 0)
        {
             router = Router.router(vertx);
             BASE_URI = MODULE_NAME+"/"+MODULE_ID;
             add_get_handler(router, "/"+BASE_URI);
        }

    // iterate through all the feedmakers to be started
    // This will start the 'setPeriodic' GET pollers and also add the required
    // BASE_URI/FEED_ID http POST handlers to the router
    for (int i=0; i<START_FEEDS.size(); i++)
        {
          start_maker(START_FEEDS.getJsonObject(i), router, BASE_URI);
        }

    // *********************************************************************
    // connect router to http_server, including the feed POST handlers added
    if (HTTP_PORT != 0)
        {
            http_server.requestHandler(router).listen(HTTP_PORT);
            logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": http server started");
        }

  } // end start()

    // **************************************************************************************
    // create handler for GET from uri - for general web access (diagnostics?) not feed data
    // **************************************************************************************
    private void add_get_handler(Router router, String uri)
    {
        router.route(HttpMethod.GET,uri).handler( ctx -> {

                HttpServerResponse response = ctx.response();
                response.putHeader("content-type", "text/html");

                response.end("<h1>TFC Rita FeedMaker at "+uri+"</h1><p>Version "+VERSION+"</p>\n");
            });
    }

    // *************************************
    // start a GET polling feed_maker with a given config
    private void start_maker(JsonObject config, Router router, String BASE_URI)
    {
        // ********************************************************************
        // Create a FeedParser for the desired feed
        // ********************************************************************

          FeedParser parser;

          if (config.getString("feed_type").equals(Constants.FEED_XML_FLAT))
          {
              parser = new ParseFeedXMLFlat(config, logger);
          }
          else if (config.getString("feed_type").equals(Constants.FEED_PLAIN))
          {
              parser = new ParseFeedPlain(config, logger);
          }
          else if (config.getString("feed_type").equals(Constants.FEED_EVENTBUS_MSG))
          {
              parser = new ParseFeedEventbusMsg(config, logger);
          }
          else if (config.getString("feed_type").equals(Constants.FEED_GTFS))
          {
              parser = new ParseFeedGTFS(config, logger);
          }
          else if (config.getString("feed_type").equals(Constants.FEED_BTJOURNEY_LOCATIONS))
          {
              parser = new ParseBTJourneyLocations(config, logger);
          }
          else if (config.getString("feed_type").equals(Constants.FEED_BTJOURNEY_JOURNEYTIMES))
          {
              parser = new ParseBTJourneyTimes(config, logger);
          }
          else if (config.getString("feed_type").equals(Constants.FEED_JSON))
          {
              parser = new ParseJson(config, logger);
          }
          else
          {
              parser = new ParseFeedText(config, logger);
          }

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
                                        ": start_maker created monitor path "+monitor_path);
            } catch (Exception e) {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                                        ": start_maker FAIL: error creating monitor path "+monitor_path);
                return;
            }
          }
          // monitor_path now exists

        // ************************************************************************************
        // if 'POST' feedmaker create a HTTP POST 'listener' for this feed at BASE_URI/FEED_ID
        // ************************************************************************************
          if (HTTP_PORT != 0 && config.getBoolean("http.post", false))
              {
                  //logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+"."+
                  //           config.getString("feed_id")+": starting POST listener");
                  add_feed_handler(router, BASE_URI, config, parser);
              }

        // *********************************************************************************
        // If 'GET' feedmaker, then start polling the defined web address with GET requests
        // *********************************************************************************
          if (config.getBoolean("http.get", false))
              {
                  logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+"."+
                             config.getString("feed_id")+": GET poller started");
                  // call immediately, then every 'period' seconds
                  get_feed(config, parser);

                  // set up periodic 'GET' requests for data (.setPeriodic requires milliseconds)
                  vertx.setPeriodic( config.getInteger("period") * 1000,
                                     id -> { get_feed(config, parser);
                                     });
              }
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
    // Here is where the essential feed POST is handled
    // create handler for POST from BASE_URI/FEED_ID
    // ************************************************
    // ************************************************
    private void add_feed_handler(Router router,
                                  String BASE_URI,
                                  JsonObject config,
                                  FeedParser parser)
    {
        final String HTTP_TOKEN = config.getString("http.token");
        final String FEED_ID = config.getString("feed_id");

        final String URI = config.getString("http.uri", "/"+BASE_URI+"/"+FEED_ID);

        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+
                   ": setting up POST listener on localhost:"+HTTP_PORT+URI);
        router.route(HttpMethod.POST,URI).handler( ctx -> {
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+
                    ": Incoming POST");            
                ctx.request().bodyHandler( buffer -> {
                        try {
                            // read the head value "X-Auth-Token" from the POST
                            String post_token = ctx.request().getHeader("X-Auth-Token");
                            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+
                                       ": X-Auth-Token="+post_token);
                            // if the token matches the config(), or config() http.token is null
                            // then parse this assumed gtfs-realtime POST data
                            if (HTTP_TOKEN==null || HTTP_TOKEN.equals(post_token))
                            {
                                process_feed(buffer, config, parser);
                            }
                            else
                            {
                                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+
                                       ": token error "+post_token+"/"+HTTP_TOKEN);
                            }
                        } catch (Exception e) {
                            logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+
                                       ": proceed_feed error");
                            logger.log(Constants.LOG_WARN, e.getMessage());
                        }

                        ctx.request().response().end("");
                    });

            });
        logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+": POST handler started");
    }

    // ************************************************************************************
    // get_feed()
    //
    // This is the routine called periodically to GET the feed from the defined web address.
    // it will pass the data to 'parser' to convert to a JsonObject to send on the EventBus
    //
    private void get_feed(JsonObject config, FeedParser parser)
    {
        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                               ": get_feed "+config.getString("http.host")+config.getString("http.uri"));

        final String FEED_ID = config.getString("feed_id");

        // get the WebClient pre-configured for this feed id
        web_clients.get(FEED_ID)
            // apply methods to this WebClient
            .get(config.getString("http.uri"))
            .putHeader("Accept-Encoding", "identity")
            .send( async_response -> {
                if (async_response.succeeded())
                {
                        Buffer buffer = async_response.result().body();

                        // print out the received GET data for LOG_LEVEL=1 (debug)
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+
                                   ": GET "+ async_response.result().statusCode() +
                                   " response length=" + buffer.length() );

                        // Now send the buffer to be processed, which may cause exception if bad data
                        try {
                          process_feed(buffer, config, parser);
                        }
                        catch (Exception e) {
                            logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+
                                       ": proceed_feed error");
                            logger.log(Constants.LOG_WARN, e.getMessage());
                        }
                }
                else // async_response failed
                {
                    // print out the received GET data for LOG_LEVEL=1 (debug)
                    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+
                               ": GET FAILED " + async_response.cause().getMessage() );
                }
            }); // end .send

    } // end get_feed()

    // ***********************************************
    // get current local time as "YYYY-MM-DD-hh-mm-ss"
    private String local_datetime_string()
    {
        LocalDateTime local_time = LocalDateTime.now();
        return local_time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"));
    }

  // *****************************************************************
  // process the received raw data
  private void process_feed(Buffer buf, JsonObject config, FeedParser parser) throws Exception
  {

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

    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                       ": write_bin_file "+bin_path+" "+filename+"."+file_suffix);
    // Write file to DATA_MONITOR
    //
    final String monitor_path = config.getString("data_monitor");
    write_monitor_file(buf, monitor_path, filename, file_suffix);

    // ********************************************************************************************
    // Finally, here is where we PARSE the incoming data and put it in the 'request_data' property
    // ********************************************************************************************

    try {
        // Parse the received data into a suitable EventBus JsonObject message
        JsonObject msg = parser.parse(buf);

        msg.put("module_name", MODULE_NAME);
        msg.put("module_id", MODULE_ID);
        msg.put("feed_id", config.getString("feed_id"));
        msg.put("filename", filename);
        msg.put("filepath", filepath);
        msg.put("ts", utc_seconds);

        // if a FEED_EVENTBUS_MSG then increment hop_count
        if (config.getString("feed_type").equals(Constants.FEED_EVENTBUS_MSG))
        {
            String hops_name = Constants.PLATFORM_PREFIX+"hop_count";
            int hops = msg.getInteger(hops_name,0);
            msg.put(hops_name, hops+1);
        }
        else // else if NOT a FEED_EVENTBUS_MSG then overwrite msg_type
        {
            msg.put("msg_type", config.getString("msg_type"));
        }

        // prev version -
        //JsonArray request_data = parser.parse_array(buf.toString());
        //msg.put("request_data", request_data);

        // debug print out the JsonObject message
        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+": prepared EventBus msg:");
        logger.log(Constants.LOG_DEBUG, msg.toString());

        String feedmaker_address = config.getString("address");

        eb.publish(feedmaker_address, msg);

        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                   ": published latest feed data to "+feedmaker_address);
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

    // validate_feeds() will validate a FeedMaker feeds config, and insert default values
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

                String FEED_ID = config.getString("feed_id");

                // http.get and http.post bools tell FeedMaker whether to GET or receive POSTS
                boolean http_get = config.getBoolean("http.get", false);
                boolean http_post = config.getBoolean("http.post", false);

                if ( !http_get && !http_post)
                    {
                        Log.log_err(MODULE_NAME+"."+MODULE_ID+".feeds."+FEED_ID+
                                    ": require http.post or http.get in config");
                        return false;
                    }

                if ( http_post && HTTP_PORT==0)
                    {
                        Log.log_err(MODULE_NAME+"."+MODULE_ID+".feeds."+FEED_ID+
                                    ": http.post requires "+MODULE_NAME+".http.port in config");
                        return false;
                    }

                // host name from which to GET data
                if (http_get && config.getString("http.host")==null)
                    {
                        Log.log_err(MODULE_NAME+"."+MODULE_ID+".feeds."+FEED_ID+
                                    ": http.host missing in config");
                        return false;
                    }

                // ssl yes/no for http request
                if (http_get && config.getBoolean("http.ssl")==null)
                    {
                        config.put("http.ssl", false);
                    }

                // http port to be used to request the data
                if (http_get && config.getInteger("http.port")==null)
                    {
                        config.put("http.port", 80);
                    }

                // period (in seconds) between successive 'get' requests for data
                if (http_get && config.getInteger("period",0)==0)
                    {
                        Log.log_err(MODULE_NAME+"."+MODULE_ID+".feeds."+FEED_ID+
                                    ": period missing in config");
                        return false;
                    }

                if (config.getString("data_bin")==null)
                    {
                        Log.log_err(MODULE_NAME+"."+MODULE_ID+".feeds."+FEED_ID+
                                    ": data_bin missing in config");
                        return false;
                    }

                // filesystem path to store the latest 'post_data.bin' file so
                // it can be monitored for inotifywait processing
                if (config.getString("data_monitor")==null)
                    {
                        Log.log_err(MODULE_NAME+"."+MODULE_ID+".feeds."+FEED_ID+
                                    ": data_monitor missing in config");
                        return false;
                    }

                if (config.getString("file_suffix")==null)
                    {
                        config.put("file_suffix",".bin");
                    }

                // create a new WebClient for this feed, and add to web_clients list
                if (http_get)
                    {
                        WebClientOptions options = new WebClientOptions()
                                                       .setDefaultHost(config.getString("http.host"))
                                                       .setDefaultPort(config.getInteger("http.port"))
                                                       .setFollowRedirects(true)
                                                       .setKeepAlive(false)
                                                       .setSsl(config.getBoolean("http.ssl"))
                                                       .setTrustAll(true);

                        WebClient client = WebClient.create(vertx, options);

                        web_clients.put(config.getString("feed_id"), client);
                    }
            }

        return true; // if we got to here then we can return ok, error would have exitted earlier
    }

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

        // web address for this FeedHandler to receive POST data messages from original source
        HTTP_PORT = config().getInteger(MODULE_NAME+".http.port",0);

        START_FEEDS = config().getJsonArray(MODULE_NAME+".feeds");

        if (!validate_feeds())
            {
                Log.log_err(MODULE_NAME+"."+MODULE_ID+": feeds config() not valid");
                return false;
            }

        return true;
    }

} // end FeedMaker class
