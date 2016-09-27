package uk.ac.cam.tfc_server.console;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// Console.java
//
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' next-generation Realtime Intelligent Traffic Analysis system
//
// Provides an HTTP server that serves the system administrator, to view eventbus events etc
//
// Listens for eventBus messages from "feed_vehicle"
//
// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Handler;

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
import io.vertx.ext.web.handler.StaticHandler;
//import io.vertx.serviceproxy.ProxyHelper;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
import io.vertx.ext.web.handler.sockjs.PermittedOptions;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandlerOptions;

// handlebars for static .hbs web template files
import io.vertx.ext.web.templ.HandlebarsTemplateEngine;

import java.io.*;
import java.time.*;
import java.time.format.*;

import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;

public class Console extends AbstractVerticle {

    private final String VERSION = "1.05";
    
    public int LOG_LEVEL; // optional in config(), defaults to Constants.LOG_INFO

    private Integer HTTP_PORT; // from config()
    private String EB_SYSTEM_STATUS; // from config()
    private String EB_MANAGER; // from config()
    private String MODULE_NAME; // from config()
    private String MODULE_ID; // from config()
    private String WEBROOT; // from config()

    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 15;
    private final int SYSTEM_STATUS_RED_SECONDS = 25;

    private Log logger; // tfc module to handle logging
    
    private EventBus eb;
    
  @Override
  public void start(Future<Void> fut) throws Exception {

    if (!get_config())
          {
              Log.log_err("Console: "+MODULE_NAME+"."+ MODULE_ID + " failed to load initial config()");
              vertx.close();
              return;
          }

    logger = new Log(LOG_LEVEL);
    
    logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": Version "+VERSION+" started on " +
                       EB_SYSTEM_STATUS+", port "+HTTP_PORT);

    eb = vertx.eventBus();
    
    HttpServer http_server = vertx.createHttpServer();

    Router router = Router.router(vertx);

    // general logging of get requests and initial hack filter
    router.route(HttpMethod.GET,"/*").handler(ctx -> {
            // wrap in 'try' block to filter out many hacking malformed URL requests
            try {
                System.out.println("Console GET request for " + ctx.request().absoluteURI());
                ctx.next();
            } catch (Exception e) {
                // will do nothing here if ctx.request.absoluteURI() fails for current request
            }
        });
    
    // *****************************************
      // create handler for eventbus bridge
    // *****************************************

    SockJSHandler ebHandler = SockJSHandler.create(vertx);

    BridgeOptions bridge_options = new BridgeOptions();
    bridge_options.addOutboundPermitted( new PermittedOptions().setAddress(EB_SYSTEM_STATUS) );

    ebHandler.bridge(bridge_options);

    router.route("/console/eb/*").handler(ebHandler);
      
    // *****************************************
    // create handler for console template pages
    // *****************************************

    final HandlebarsTemplateEngine template_engine = HandlebarsTemplateEngine.create();
    
    router.route(HttpMethod.GET, "/console").handler( ctx -> {

            ctx.put("config_version", VERSION);
            
            ctx.put("config_eb_system_status", EB_SYSTEM_STATUS);
            
            ctx.put("config_module_id", MODULE_ID);
            
            template_engine.render(ctx, "templates/console.hbs", res -> {
                    if (res.succeeded())
                    {
                        ctx.response().end(res.result());
                    }
                    else
                    {
                        ctx.fail(res.cause());
                    }
                });
        } );

    // create handler for static resources

    StaticHandler static_handler = StaticHandler.create();
    static_handler.setWebRoot(WEBROOT);
    static_handler.setCachingEnabled(false);
    router.route(HttpMethod.GET, "/static/*").handler( static_handler );

    System.out.println("Console."+MODULE_ID+": StaticHandler "+WEBROOT+" Started for /static/*");
    // connect router to http_server

      // general logging of get requests
      router.route(HttpMethod.GET,"/*").handler(ctx -> {
            System.out.println("Console GET request not matched for " + ctx.request().absoluteURI());
            ctx.next();
        });
    
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

    // Load initialization global constants defining this module from config()
    private boolean get_config()
    {
        // config() values needed by all TFC modules are:
        // module.name e.g. "console"
        // module.id e.g. "A"
        // eb.system_status - String eventbus address for system status messages
        // eb.manager - evenbus address to subscribe to for system management messages

        MODULE_NAME = config().getString("module.name"); // "console"
        if (MODULE_NAME==null)
            {
                System.err.println("Console: no module.name in config()");
                return false;
            }
        
        MODULE_ID = config().getString("module.id"); // A, B, ...
        if (MODULE_ID==null)
            {
                System.err.println(MODULE_NAME+": no module.id in config()");
                return false;
            }

        // set logging level
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
                System.err.println(MODULE_NAME+"."+MODULE_ID+": no eb.system_status in config()");
                return false;
            }

        // system control address - commands are broadcast on this
        EB_MANAGER = config().getString("eb.manager");
        if (EB_MANAGER==null)
            {
                System.err.println(MODULE_NAME+"."+MODULE_ID+" no eb.manager in config()");
                return false;
            }

        // port for user browser access to this Rita
        HTTP_PORT = config().getInteger(MODULE_NAME+".http.port");
        if (HTTP_PORT==null)
            {
                System.err.println(MODULE_NAME+"."+MODULE_ID+": no "+MODULE_NAME+".http.port in config()");
                return false;
            }

        // where the built-in webserver will find static files
        WEBROOT = config().getString(MODULE_NAME+".webroot");
        return true;
    }
    
} // end class Console
