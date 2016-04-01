package uk.ac.cam.tfc_server.zonemanager;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// ZoneManager.java
// Version 0.02
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' next-generation Realtime Intelligent Traffic Analysis system
//
// Listens to events on the EB_ZONE_MANAGER address and launches Zone verticles
//
// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.DeploymentOptions;

import io.vertx.core.file.FileSystem;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.buffer.Buffer;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

import java.io.*;
import java.time.*;
import java.time.format.*;

import java.util.ArrayList;

public class ZoneManager extends AbstractVerticle {

  private String EB_SYSTEM_STATUS; // from config()
  private String MODULE_NAME; // from config()
  private String MODULE_ID; // from config()
  private ArrayList<String> START_ZONES; // from config()

    // debug these should be coming from manager messages
  private String ZONE_ADDRESS; // from config() - address for Zones to publish to
  private String ZONE_FEED; // from config() - address for Zones to subscribe to
    
  private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
  private final int SYSTEM_STATUS_AMBER_SECONDS = 15;
  private final int SYSTEM_STATUS_RED_SECONDS = 25;

  private EventBus eb = null;

  private JsonObject zone_config; // Vertx config for Zone
    
  @Override
  public void start(Future<Void> fut) throws Exception {

    // Get src/main/conf/tfc_server.conf config values for module
    if (!get_config())
        {
            System.err.println("ZoneManager: problem loading config");
            vertx.close();
            return;
        }

    System.out.println("ZoneManager: started using "+MODULE_NAME+"."+MODULE_ID);

    eb = vertx.eventBus();

    //debug -- zone.address and zone.feed should come from manager messages
    DeploymentOptions zone_options = new DeploymentOptions();
    JsonObject conf = new JsonObject();
    if (ZONE_ADDRESS != null)
        {
            conf.put("zone.address", ZONE_ADDRESS);
        }
    if (ZONE_FEED != null)
        {
            conf.put("zone.feed", ZONE_FEED);
        }
    zone_options.setConfig(conf);

    for (int i=0; i<START_ZONES.size(); i++)
        {
            final String zone_id = START_ZONES.get(i);

            vertx.deployVerticle("service:uk.ac.cam.tfc_server.zone."+zone_id,
                                 zone_options,
                                 res -> {
                    if (res.succeeded()) {
                        System.out.println("ZoneManager: Zone "+zone_id+ "started");
                    } else {
                        System.err.println("ZoneManager: failed to start Zone " + zone_id);
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

  } // end start()

    // Load initialization global constants defining this Zone from config()
    private boolean get_config()
    {
        // config() values needed by all TFC modules are:
        //   tfc.module_id - unique module reference to be used by this verticle
        //   eb.system_status - String eventbus address for system status messages

        MODULE_NAME = config().getString("module.name"); // "zonemanager"
        if (MODULE_NAME==null)
            {
                return false;
            }
        
        MODULE_ID = config().getString("module.id"); // A, B, ...

        EB_SYSTEM_STATUS = config().getString("eb.system_status");

        //debug test for bad config

        START_ZONES = new ArrayList<String>();
        JsonArray zone_list = config().getJsonArray(MODULE_NAME+".start");
        for (int i=0; i<zone_list.size(); i++)
            {
                START_ZONES.add(zone_list.getString(i));
            }

        ZONE_ADDRESS = config().getString(MODULE_NAME+".zone.address");

        ZONE_FEED = config().getString(MODULE_NAME+".zone.feed");
        
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
    
    
} // end class ZoneManager
