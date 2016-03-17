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
  private final int HTTP_PORT = 8081;
  private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
  private EventBus eb = null;
    
  @Override
  public void start(Future<Void> fut) throws Exception {

    boolean ok = true; // simple boolean to flag an abort during startup

    System.out.println("Console started! ");

    eb = vertx.eventBus();
    
    if (ok)
    {
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

        response.end("<h1>TFC Console</h1><p>Vertx-Web!</p>");
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
      static_handler.setWebRoot("webroot");
      static_handler.setCachingEnabled(false);
      router.route(HttpMethod.GET, "/*").handler( static_handler );

      // connect router to http_server
      
      http_server.requestHandler(router::accept).listen(HTTP_PORT);

      // create listener for eventbus 'feed_vehicle' messages
      eb.consumer("feed_vehicle", message -> {
              JsonObject feed_message = new JsonObject(message.body().toString());
              System.out.println("Console feed_vehicle message #records: "+
                                 String.valueOf(feed_message.getJsonArray("entities").size()));
              //debug - sending system_status message on behalf of feedhandler
              eb.publish("system_status", "{ \"module_name\": \"feedhandler\", \"status\": \"UP\" }");
              handle_feed(feed_message);
          });

      // create listener for eventbus 'console_in' messages
      eb.consumer("console_in", message -> {
              System.out.println("Console_in: "+message.body());
          });

      // create listener for eventbus 'system_status' messages
      eb.consumer("system_status", message -> {
              System.out.println("system_status: "+message.body());
              eb.publish("console_out_system_status", message.body());
          });

      // send periodic "system_status" messages
      vertx.setPeriodic(SYSTEM_STATUS_PERIOD, id -> {
              System.out.println("Sending system_status UP");
              // publish { "module_name": "console", "status": "UP" } on address "system_status"
              eb.publish("system_status", "{ \"module_name\": \"console\", \"status\": \"UP\" }");
          });
    } // end if (ok)
  } // end start()

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
