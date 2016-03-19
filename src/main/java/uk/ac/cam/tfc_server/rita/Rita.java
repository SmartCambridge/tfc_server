package uk.ac.cam.tfc_server.rita;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// Rita.java
// Version 0.02
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
  private String EB_RITA; // from config()
  private String EB_SYSTEM_STATUS; // from config()
  private String MODULE_NAME; // from config()
  private String MODULE_ID; // from config()
  private int SYSTEM_STATUS_AMBER_SECONDS = 15;
  private int SYSTEM_STATUS_RED_SECONDS = 25;
    
  private EventBus eb = null;

  private JsonObject zone_config; // Vertx config for cam_test Zone
    
  @Override
  public void start(Future<Void> fut) throws Exception {

    System.out.println("Rita started! ");

    // Get src/main/conf/tfc_server.conf config values for module
    if (!get_config())
        {
            System.err.println("Rita: problem loading config");
            vertx.close();
            return;
        }

    eb = vertx.eventBus();

    //debug
    // get test config options for Zone from conf file given to Rita
    DeploymentOptions zone_options = new DeploymentOptions()
        .setConfig( get_zone_config() );
    
    vertx.deployVerticle("uk.ac.cam.tfc_server.zone.Zone",
                         zone_options,
                         res -> {
            if (res.succeeded()) {
                System.out.println("Rita: Zone started");
            } else {
                System.err.println("Rita: failed to start Zone");
                fut.fail(res.cause());
            }
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

    // Load initialization global constants defining this Zone from config()
    private boolean get_config()
    {
        // config() values needed by all TFC modules are:
        //   tfc.module_id - unique module reference to be used by this verticle
        //   eb.system_status - String eventbus address for system status messages

        MODULE_NAME = config().getString("module.name");
        
        MODULE_ID = config().getString("module.id");

        EB_SYSTEM_STATUS = config().getString("eb.system_status","system_status_test");

        EB_RITA = config().getString("eb.rita", "rita_test");
        
        return true;
    }

    private JsonObject get_zone_config()
    {
        // config given to Zone starts with original system config
        JsonObject zone_config = config();

        zone_config.put("module.name", "zone");
        
        String zone_id =  config().getString("zone.cam_test.id");
        
        zone_config.put("zone.id", zone_id);
        
        zone_config.put("module.id", zone_id);
        
        zone_config.put("zone.name", config().getString("zone.cam_test.name"));

        zone_config.put("zone.path", config().getJsonArray("zone.cam_test.path"));
        
        zone_config.put("zone.center", config().getJsonObject("zone.cam_test.center"));

        zone_config.put("zone.zoom", config().getInteger("zone.cam_test.zoom"));

        zone_config.put("zone.finish_index", config().getInteger("zone.cam_test.finish_index"));

        return zone_config;
    }
    
    
} // end class Rita
