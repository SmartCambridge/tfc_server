package uk.ac.cam.tfc_server.rita;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// Rita.java
// Version 0.01
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' next-generation Realtime Intelligent Traffic Analysis system
//
// Provides an HTTP server that serves the system administrator, to view eventbus events etc
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

public class Rita extends AbstractVerticle {
  //debug pick up in config()
  private final int HTTP_PORT = 8084;
  private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
  private final String EB_RITA = "tfc.rita";
    
  private final String module_name = "rita";
    
  private EventBus eb = null;
    
  @Override
  public void start(Future<Void> fut) throws Exception {

    System.out.println("Rita started! ");

    eb = vertx.eventBus();

    vertx.deployVerticle("uk.ac.cam.tfc_server.zone.Zone", res -> {
            if (res.succeeded()) {
                System.out.println("Rita: Zone started");
            } else {
                System.err.println("Rita: failed to start Zone");
                fut.fail(res.cause());
            }
        });
    
    // send periodic "system_status" messages
    vertx.setPeriodic(SYSTEM_STATUS_PERIOD, id -> {
          eb.publish("system_status", "{ \"module_name\": \""+module_name+"\", \"status\": \"UP\" }");
      });

    // *************************************************************************************
    // *************************************************************************************
    // *********** Start Rita web server (incl EventBus Bridge)                 ************
    // *************************************************************************************
    // *************************************************************************************
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

    response.end("<h1>TFC Rita</h1><p>Vertx-Web!</p>");
    });

    // create handler for eventbus bridge

    SockJSHandler ebHandler = SockJSHandler.create(vertx);

    PermittedOptions inbound_permitted = new PermittedOptions().setAddress("rita_in");
    BridgeOptions bridge_options = new BridgeOptions();
    bridge_options.addOutboundPermitted( new PermittedOptions().setAddress("rita_out") );

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

    // create listener for eventbus 'console_in' messages
    eb.consumer("rita_in", message -> {
          System.out.println("Rita_in: "+message.body());
      });

  } // end start()

} // end class Console
