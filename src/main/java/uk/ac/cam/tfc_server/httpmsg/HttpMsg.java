package uk.ac.cam.tfc_server.httpmsg;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// HttpMsg.java
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' next-generation Adaptive City Platform
//
// Receives data via http POST to 'module_name/module_id/eventbus_address/to_module_name/to_module_id'
// Sends data on EventBus <address> (from the URL)
// The POST data must be valid json, and will be ignored otherwise.
//
// POSTs are verified using a header "X-Auth-Token" that must match config http.token
//
// This module is similar in function to FeedHandler and FeedMaker except it does NOT
// archive the received data to the filesystem.
//
// Config values are read from provided vertx config() json file, e.g. see README.md
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

public class HttpMsg extends AbstractVerticle {

    private final String VERSION = "0.06";
    
    // from config()
    private String MODULE_NAME;       // config module.name - normally "httpmsg"
    private String MODULE_ID;         // config module.id
    private String EB_SYSTEM_STATUS;  // config eb.system_status
    private String EB_MANAGER;        // config eb.manager
    
    // maker configs:
    private JsonArray START_FEEDS;    // config module_name.feeds parameters
    
    public int LOG_LEVEL;             // optional in config(), defaults to Constants.LOG_INFO

    private int HTTP_PORT;            // config httpmsg.http.port

    // local constants
    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 25;
    private final int SYSTEM_STATUS_RED_SECONDS = 35;

    // global vars
    private EventBus eb = null;

    private Log logger;
    
  @Override
  public void start(Future<Void> fut) throws Exception {

    Router router = null;

    String BASE_URI = null;

    // load HttpMsg initialization values from config()
    if (!get_config())
          {
              Log.log_err("HttpMsg: "+ MODULE_ID + " failed to load initial config()");
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

    // create request router for webserver
    if (HTTP_PORT != 0)
        {
             router = Router.router(vertx);
             BASE_URI = MODULE_NAME+"/"+MODULE_ID;
             add_get_handler(router, "/"+BASE_URI);
        }

    // iterate through all the feedmakers to be started
    // BASE_URI/FEED_ID http POST handlers to the router
    for (int i=0; i<START_FEEDS.size(); i++)
        {
          start_feed(START_FEEDS.getJsonObject(i), router, BASE_URI);
        }

    // *********************************************************************
    // connect router to http_server, including the feed POST handlers added
    http_server.requestHandler(router).listen(HTTP_PORT);
    logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+
               ": http server started on :"+HTTP_PORT+"/"+MODULE_NAME+"/"+MODULE_ID);
    
  } // end start()

    // ************************************
    // create handler for GET from uri
    // ************************************
    private void add_get_handler(Router router, String uri)
    {
        router.route(HttpMethod.GET,uri).handler( ctx -> {

                HttpServerResponse response = ctx.response();
                response.putHeader("content-type", "text/html");

                response.end("<h1>HttpMsg at "+uri+"</h1><p>Version "+VERSION+"</p>\n");
            });
    }

    // *************************************
    // start a feed maker with a given config
    private void start_feed(JsonObject config, Router router, String BASE_URI)
    {
          // create a HTTP POST 'listener' for this feed at BASE_URI/FEED_ID
          add_feed_handler(router, BASE_URI, config);
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

    // *************************************************
    // *************************************************
    // Here is where we set up handlers for the messages
    // create handler for POST from BASE_URI/FEED_ID
    // *************************************************
    // *************************************************
    private void add_feed_handler(Router router, 
                                  String BASE_URI,
                                  JsonObject config)
    {
        final String HTTP_TOKEN = config.getString("http.token");
        final String ADDRESS = config.getString("address");

        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                       ": setting up POST listener on "+"/"+BASE_URI+"/"+ADDRESS);
        router
            .route(HttpMethod.POST,"/"+BASE_URI+"/"+ADDRESS+"/:to_module_name/:to_module_id")
            .handler(ctx -> handle_httpmsg(ctx, ADDRESS, HTTP_TOKEN, config));
        router
            .route(HttpMethod.POST,"/"+BASE_URI+"/"+ADDRESS)
            .handler(ctx -> handle_httpmsg(ctx, ADDRESS, HTTP_TOKEN, config));
    }

    // ************************************************
    // ************************************************
    // And here is the actual HttpMsg handler
    // ************************************************
    // ************************************************
    private void handle_httpmsg(RoutingContext ctx, String ADDRESS, String HTTP_TOKEN, JsonObject config)
    {
        ctx.request().bodyHandler( buffer -> {
                try {
                    // read the head value "X-Auth-Token" from the POST
                    String post_token = ctx.request().getHeader("X-Auth-Token");
                    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                               ": "+ADDRESS+ " POST X-Auth-Token="+post_token);
                    // if the token matches the config(), or config() http.token is null
                    // then process this particular post
                    if (HTTP_TOKEN==null || HTTP_TOKEN.equals(post_token))
                    {
                        String to_module_name = ctx.request().getParam("to_module_name");
                        String to_module_id = ctx.request().getParam("to_module_id");
                        process_httpmsg(buffer, to_module_name, to_module_id, config);
                    }
                    else
                    {
                        logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                               ": "+ADDRESS+" X-Auth-Token mis-match");
                    }
                } catch (Exception e) {
                    logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                               ": "+ADDRESS+" proceed_feed error");
                    logger.log(Constants.LOG_WARN, e.getMessage());
                }

                ctx.request().response().end("");
            });
    }
    
  // *****************************************************************
  // process the received POST, and send as EventBus message
  private void process_httpmsg(Buffer buf, String to_module_name, String to_module_id, JsonObject config) throws Exception 
  {
    // Copy the received data into a suitable EventBus JsonObject message
    JsonObject msg;

    try {            
        // try parsing the POST message as JSON, exception on fail
        msg = new JsonObject(buf.toString());

        // get the destination EventBus address from the module config
        String address = config.getString("address");

        // Embed module_name and module_id for THIS module into the EventBus message
        msg.put("module_name", MODULE_NAME);
        msg.put("module_id", MODULE_ID);

        // Embed module_name and module_id for DESTINATION  module into the EventBus message
        if (to_module_name != null)
        {
            msg.put("to_module_name", to_module_name);
        }
        if (to_module_id != null)
        {
            msg.put("to_module_id", to_module_id);
        }
        
        // debug print out the JsonObject message
        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                   ": prepared EventBus msg for "+address+":");
        logger.log(Constants.LOG_DEBUG, msg.toString());

        // ******************************************************************
        // ******  SEND THE POST DATA ON EVENTBUS   *************************
        // ******************************************************************
        eb.publish(address, msg);
    
    }
    catch (Exception e) {
        logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                   ": exception raised during processing of http post "+config.getString("address")+":");
        logger.log(Constants.LOG_WARN, e.getMessage());
    }
  } // end process_httpmsg()

    // Load initialization global constants defining this HttpMsg from config()
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
                Log.log_err("HttpMsg: config() not set");
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

        // web address to receive POST data messages from original source
        HTTP_PORT = config().getInteger(MODULE_NAME+".http.port",0);
        if (HTTP_PORT == 0)
            {
                Log.log_err(MODULE_NAME+"."+MODULE_ID+": "+MODULE_NAME+".http.port config() not set");
                return false;
            }

        START_FEEDS = config().getJsonArray(MODULE_NAME+".feeds");
        
        return true;
    }

} // end HttpMsg class
