package uk.ac.cam.tfc_server.rita;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// Rita.java
//
// RITA is the user "master controller" of the tfc modules...
// Based on user requests via the http UI, RITA will spawn feedplayers and zones, to
// display the analysis in real time on the user browser.
//
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
import java.util.*;

public class Rita extends AbstractVerticle {

  private int HTTP_PORT; // from config()
  private String RITA_ADDRESS; // from config()
  private String EB_SYSTEM_STATUS; // from config()
  private String EB_MANAGER; // from config()
  private String MODULE_NAME; // from config()
  private String MODULE_ID; // from config()
  private String WEBROOT; // from config()
    
    //debug - these may come from user commands
    private ArrayList<String> FEEDPLAYERS; // from config()
    private ArrayList<String> ZONEMANAGERS; // from config()
    
  private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
  private final int SYSTEM_STATUS_AMBER_SECONDS = 15;
  private final int SYSTEM_STATUS_RED_SECONDS = 25;
    
  private EventBus eb = null;

  @Override
  public void start(Future<Void> fut) throws Exception {

    // Get src/main/conf/tfc_server.conf config values for module
    if (!get_config())
        {
            System.err.println("Rita: problem loading config");
            vertx.close();
            return;
        }

    System.out.println("Rita starting as "+MODULE_NAME+"."+MODULE_ID);

    eb = vertx.eventBus();

    //debug
    // get test config options for FeedPlayers from conf file given to Rita
    
    for (int i=0; i<FEEDPLAYERS.size(); i++)
        {
            final String feedplayer_id = FEEDPLAYERS.get(i);
            //debug -- also should get start commands from eb.zonemanager.X
            DeploymentOptions feedplayer_options = new DeploymentOptions();

            vertx.deployVerticle("service:uk.ac.cam.tfc_server.feedplayer."+feedplayer_id,
                                 feedplayer_options,
                                 res -> {
                    if (res.succeeded()) {
                        System.out.println("Rita"+MODULE_ID+": FeedPlayer "+feedplayer_id+ "started");
                    } else {
                        System.err.println("Rita"+MODULE_ID+": failed to start FeedPlayer " + feedplayer_id);
                        fut.fail(res.cause());
                    }
                });
        }
    
    //debug
    for (int i=0; i<ZONEMANAGERS.size(); i++)
        {
            final String zonemanager_id = ZONEMANAGERS.get(i);
            //debug -- also should get start commands from eb.zonemanager.X
            DeploymentOptions zonemanager_options = new DeploymentOptions();

            vertx.deployVerticle("service:uk.ac.cam.tfc_server.zonemanager."+zonemanager_id,
                                 zonemanager_options,
                                 res -> {
                    if (res.succeeded()) {
                        System.out.println("Rita"+MODULE_ID+": ZoneManager "+zonemanager_id+ "started");
                    } else {
                        System.err.println("Rita"+MODULE_ID+": failed to start ZoneManager " + zonemanager_id);
                        fut.fail(res.cause());
                    }
                });
        }
    
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

    //debug rita_in/out EB names should be in config()
    PermittedOptions inbound_permitted = new PermittedOptions().setAddress("rita_in");
    BridgeOptions bridge_options = new BridgeOptions();
    bridge_options.addOutboundPermitted( new PermittedOptions().setAddress("rita_out") );

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
    eb.consumer("rita_in", message -> {
          System.out.println("Rita_in: "+message.body());
      });

    //debug !! wrong to hardcode the feedplayer eventbus address
    //debug also hardcoded "rita_out"
    // create listener for eventbus FeedPlayer messages
    // and send them to the browser via rita_out
    eb.consumer("tfc.feedplayer.B", message -> {
            eb.send("rita_out", message.body());
      });

  } // end start()

    // Load initialization global constants defining this Zone from config()
    private boolean get_config()
    {
        // config() values needed by all TFC modules are:
        //   tfc.module_id - unique module reference to be used by this verticle
        //   eb.system_status - String eventbus address for system status messages

        MODULE_NAME = config().getString("module.name"); // "rita"
        if (MODULE_NAME==null)
            {
                return false;
            }
        
        MODULE_ID = config().getString("module.id"); // A, B, ...

        EB_SYSTEM_STATUS = config().getString("eb.system_status");
        EB_MANAGER = config().getString("eb.manager");

        RITA_ADDRESS = config().getString(MODULE_NAME+".address");

        HTTP_PORT = config().getInteger(MODULE_NAME+".http.port");

        WEBROOT = config().getString(MODULE_NAME+".webroot");
        //debug test for bad config
        
        FEEDPLAYERS = new ArrayList<String>();
        JsonArray feedplayer_list = config().getJsonArray(MODULE_NAME+".feedplayers");
        for (int i=0; i<feedplayer_list.size(); i++)
            {
                FEEDPLAYERS.add(feedplayer_list.getString(i));
            }
        
        ZONEMANAGERS = new ArrayList<String>();
        JsonArray zonemanager_list = config().getJsonArray(MODULE_NAME+".zonemanagers");
        for (int i=0; i<zonemanager_list.size(); i++)
            {
                ZONEMANAGERS.add(zonemanager_list.getString(i));
            }
        
        return true;
    }

    private JsonObject make_zone_config()
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
