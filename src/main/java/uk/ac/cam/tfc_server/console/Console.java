package uk.ac.cam.tfc_server.console;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// Console.java
// Version 0.04
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

import java.io.*;
import java.time.*;
import java.time.format.*;

public class Console extends AbstractVerticle {
  private int HTTP_PORT; // from config()
  private String EB_SYSTEM_STATUS; // from config()
  private String EB_MANAGER; // from config()
  private String MODULE_NAME; // from config()
  private String MODULE_ID; // from config()
  private String WEBROOT; // from config()
    
  private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
  private final int SYSTEM_STATUS_AMBER_SECONDS = 15;
  private final int SYSTEM_STATUS_RED_SECONDS = 25;

  private EventBus eb = null;
    
  @Override
  public void start(Future<Void> fut) throws Exception {

    if (!get_config())
        {
            System.err.println("Console: problem loading config");
            vertx.close();
            return;
        }

    System.out.println("Console starting as "+MODULE_NAME+"."+MODULE_ID);

    eb = vertx.eventBus();
    
      HttpServer http_server = vertx.createHttpServer();

      Router router = Router.router(vertx);

      // create handler for browser socket 

      SockJSHandlerOptions options = new SockJSHandlerOptions().setHeartbeatInterval(2000);
      
      SockJSHandler wsHandler = SockJSHandler.create(vertx, options);

      wsHandler.socketHandler( ws -> {
             ws.handler(ws::write);
          });

      router.route("/ws/*").handler(wsHandler);

      // create handler for embedded page
      
      router.route("/home").handler( routingContext -> {

        HttpServerResponse response = routingContext.response();
        response.putHeader("content-type", "text/html");

        response.end("<h1>TFC Console."+MODULE_ID+"</h1><p>Vertx-Web!</p>");
      });

      // create handler for eventbus bridge

      SockJSHandler ebHandler = SockJSHandler.create(vertx);

      PermittedOptions inbound_permitted = new PermittedOptions().setAddress("console_in");
      BridgeOptions bridge_options = new BridgeOptions();
      bridge_options.addOutboundPermitted( new PermittedOptions().setAddress("console_out") );
      bridge_options.addOutboundPermitted( new PermittedOptions().setAddress("console_out_system_status") );

      bridge_options.addInboundPermitted(inbound_permitted);

      ebHandler.bridge(bridge_options);

      router.route("/eb/*").handler(ebHandler);
      
      // create handler for static pages
      
      StaticHandler static_handler = StaticHandler.create();
      static_handler.setWebRoot(WEBROOT);
      static_handler.setCachingEnabled(false);
      router.route(HttpMethod.GET, "/*").handler( static_handler );

      // connect router to http_server
      
      http_server.requestHandler(router::accept).listen(HTTP_PORT);

      // create listener for eventbus 'console_in' messages
      eb.consumer("console_in", message -> {
              System.out.println("Console_in: "+message.body());
          });

      // create listener for eventbus 'system_status' messages
      eb.consumer(EB_SYSTEM_STATUS, message -> {
              System.out.println("system_status: "+message.body());
              eb.publish("console_out_system_status", message.body());
          });
    
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
                System.err.println("Console: no module.id in config()");
                return false;
            }

        // common system status reporting address, e.g. for UP messages
        // picked up by Console
        EB_SYSTEM_STATUS = config().getString("eb.system_status");
        if (EB_SYSTEM_STATUS==null)
            {
                System.err.println("Console: no eb.system_status in config()");
                return false;
            }

        // system control address - commands are broadcast on this
        EB_MANAGER = config().getString("eb.manager");
        if (EB_MANAGER==null)
            {
                System.err.println("Console: no eb.manager in config()");
                return false;
            }

        // port for user browser access to this Rita
        HTTP_PORT = config().getInteger(MODULE_NAME+".http.port");

        // where the built-in webserver will find static files
        WEBROOT = config().getString(MODULE_NAME+".webroot");
        return true;
    }
    
    // process the POST gtfs binary data
  private void serve_page(HttpServerRequest request)
    {
        System.out.println("Console: serve_page");
        LocalDateTime local_time = LocalDateTime.now();
        request.response().end("<h1>TFC Console</h1> " +
                "<p>Vert.x 3 application</p");
        
    } // end serve_page()

  private void handle_feed(JsonObject feed_message)
    {
        //PositionRecord  pos_record = new PositionRecord(pos_records.get(0));
        String filename = feed_message.getString("filename");
        System.out.println("Console feed filename = " + filename);
        eb.publish("console_out","{ \"filename\": " + filename + "}");
    }
} // end class Console
