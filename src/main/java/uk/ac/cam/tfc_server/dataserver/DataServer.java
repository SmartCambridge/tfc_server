package uk.ac.cam.tfc_server.dataserver;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// DataServer.java
//
// Serves template 'data' http pages with NO realtime socket or eventbus bridge to browser
//
// This is to provide as robust as possible a platform for most people viewing the data
//
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' next-generation Realtime Intelligent Traffic Analysis system
//
//
// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.DeploymentOptions;

import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpMethod;

import io.vertx.core.file.FileSystem;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.buffer.Buffer;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

// vertx web, service proxy, sockjs eventbus bridge
import io.vertx.ext.web.Router;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.StaticHandler;

import java.io.*;
import java.time.*;
import java.time.format.*;
import java.util.*;

// other tfc_server classes
import uk.ac.cam.tfc_server.util.Constants;
import uk.ac.cam.tfc_server.util.Log;

public class DataServer extends AbstractVerticle {

    // constants from vertx config json
    private Integer HTTP_PORT; // from config()

    private String EB_SYSTEM_STATUS; // from config()
    private String EB_MANAGER; // from config()
    public  String MODULE_NAME; // from config() // used in page servers e.g. DataPlot, DataMap
    public  String MODULE_ID; // from config()
    private String WEBROOT; // from config()
    public String GOOGLE_MAP_API_KEY; // from config() // also used in DataMap

    private int    LOG_LEVEL; // from config(), defaults to Constants.LOG_INFO
    
    public String DATA_PATH; // from config() base filesystem path to data

    public String FEED_ID = "vix"; //debug until we manage alternative feeds properly
    
    // Globals
    public String BASE_URI; // used as template parameter for web pages, built from config()

    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 25;
    private final int SYSTEM_STATUS_RED_SECONDS = 35;

    public  Log logger;

    // Vertx event bus
    private EventBus eb = null; // at least for system_status messages, not for the browser

    @Override
    public void start() throws Exception
    {
    // Get src/main/conf/tfc_server.conf config values for module
    if (!get_config())
        {
            System.err.println("DataServer: problem loading config");
            vertx.close();
            return;
        }

    logger = new Log(LOG_LEVEL);
    
    logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+
                       ": started on port "+HTTP_PORT );

    BASE_URI = MODULE_NAME; // typically 'dataserver'

    eb = vertx.eventBus();

    // send periodic "system_status" messages
    init_system_status();
    
    // *************************************************************************************
    // *************************************************************************************
    // *********** Start DataServer web server (incl Socket and EventBus Bridge)      ************
    // *************************************************************************************
    // *************************************************************************************
    HttpServer http_server = vertx.createHttpServer();

    Router router = Router.router(vertx);

    // ********************************
    // create handler for embedded page
    // ********************************

    router.route("/"+BASE_URI+"/home").handler( routingContext -> {

        HttpServerResponse response = routingContext.response();
        response.putHeader("content-type", "text/html");

        response.end("<h1>DataServer."+MODULE_ID+"</h1><p>Vertx-Web!</p>");
    });

    // ********************************
    // create handler for APIs
    // ********************************

    ZoneAPI zone_api = new ZoneAPI(vertx, this, router);
    
    ParkingAPI parking_api = new ParkingAPI(vertx, this, router);
    
    FeedAPI feed_api = new FeedAPI(vertx, this, router);
    
    AQAPI aq_api = new AQAPI(vertx, this, router);
    
    // ********************************
    // create handler for static pages
    // ********************************

    StaticHandler static_handler = StaticHandler.create();
    static_handler.setWebRoot(WEBROOT);
    static_handler.setCachingEnabled(false);
    router.route(HttpMethod.GET, "/static/*").handler( static_handler );

    logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+
               ": static handler using "+WEBROOT);
    
    // ********************************
    // connect router to http_server
    // ********************************

    http_server.requestHandler(router).listen(HTTP_PORT);

  } // end start()

    // *******************************************************************************
    // *******************************************************************************
    // *******************************************************************************
    
    // Set periodic timer to broadcast "system UP" status messages to EB_SYSTEM_STATUS address
    private void init_system_status()
    {
    vertx.setPeriodic(SYSTEM_STATUS_PERIOD, id -> {
      eb.publish(EB_SYSTEM_STATUS,
                 "{ \"module_name\": \""+MODULE_NAME+"\"," +
                   "\"module_id\": \""+MODULE_ID+"\"," +
                   "\"status\": \"UP\"," +
                   "\"status_amber_seconds\": "+String.valueOf( SYSTEM_STATUS_AMBER_SECONDS ) + "," +
                   "\"status_red_seconds\": "+String.valueOf( SYSTEM_STATUS_RED_SECONDS ) +
                 "}" );
      });
    }
    
    // Load initialization global constants defining this module from config()
    private boolean get_config()
    {
        // config() values needed by all TFC modules are:
        // module.name e.g. "dataserver"
        // module.id e.g. "A"
        // eb.system_status - String eventbus address for system status messages
        // eb.manager - evenbus address to subscribe to for system management messages

        MODULE_NAME = config().getString("module.name"); // "dataserver"
        if (MODULE_NAME==null)
            {
                Log.log_err("DataServer: no module.name in config()");
                return false;
            }
        
        MODULE_ID = config().getString("module.id"); // A, B, ...
        if (MODULE_ID==null)
            {
                Log.log_err("DataServer: no module.id in config()");
                return false;
            }

        LOG_LEVEL = config().getInteger(MODULE_NAME+".log_level", 0);
        if (LOG_LEVEL==0)
            {
                LOG_LEVEL = Constants.LOG_INFO;
            }
        
        // common system status reporting address, e.g. for UP messages
        // picked up by Console
        EB_SYSTEM_STATUS = config().getString("eb.system_status");
        if (EB_SYSTEM_STATUS==null)
            {
                Log.log_err(MODULE_NAME+"."+MODULE_ID+": no eb.system_status in config()");
                return false;
            }

        // system control address - commands are broadcast on this
        EB_MANAGER = config().getString("eb.manager");
        if (EB_MANAGER==null)
            {
                Log.log_err(MODULE_NAME+"."+MODULE_ID+": no eb.manager in config()");
                return false;
            }

        // port for user browser access to this DataServer
        HTTP_PORT = config().getInteger(MODULE_NAME+".http.port");
        if (HTTP_PORT==null)
            {
                Log.log_err(MODULE_NAME+"."+MODULE_ID+": no "+MODULE_NAME+".http.port in config()");
                return false;
            }

        // where the built-in webserver will find static files
        WEBROOT = config().getString(MODULE_NAME+".webroot");
        if (WEBROOT==null)
            {
                Log.log_err(MODULE_NAME+"."+MODULE_ID+": no "+MODULE_NAME+".webroot in config()");
                return false;
            }

        // where the built-in webserver will find static files
        DATA_PATH = config().getString(MODULE_NAME+".data_path");
        if (DATA_PATH==null)
            {
                Log.log_err(MODULE_NAME+"."+MODULE_ID+": no "+MODULE_NAME+".data_path in config()");
                return false;
            }

        // where the built-in webserver will find static files
        GOOGLE_MAP_API_KEY = config().getString(MODULE_NAME+".google_map_api_key");
        if (GOOGLE_MAP_API_KEY==null)
            {
                Log.log_err(MODULE_NAME+"."+MODULE_ID+": no "+MODULE_NAME+".google_map_api_key in config()");
                return false;
            }

        return true;
    }

} // end class DataServer

