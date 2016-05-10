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

import uk.ac.cam.tfc_server.util.Constants;

public class ZoneManager extends AbstractVerticle {

  private String EB_SYSTEM_STATUS; // from config()
  private String EB_MANAGER; // from config()
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
    JsonObject zone_conf = new JsonObject();
    if (EB_SYSTEM_STATUS != null)
        {
            // All zones will use this address to transmit 'status up' messages
            zone_conf.put("eb.system_status", EB_SYSTEM_STATUS);
        }
    if (EB_MANAGER != null)
        {
            // All zones will use this address to exchange management/control messages
            zone_conf.put("eb.manager", EB_MANAGER);
        }
    if (ZONE_FEED != null)
        {
            // All zones will subscribe to this address to get vehicle position messages
            zone_conf.put("zone.feed", ZONE_FEED);
        }

    // iterate through all the zones to be started
    for (int i=0; i<START_ZONES.size(); i++)
        {
            // get zone_id for this zone
            final String zone_id = START_ZONES.get(i);
            // Each zone has a unique 'local' eventbus address which will be used for
            // just this zone to send all its messages (e.g. vehicle entered, exitted, completed)
            String ZONE_ADDRESS_LOCAL = ZONE_ADDRESS+"."+zone_id;
            zone_conf.put("zone.address", ZONE_ADDRESS_LOCAL);
                          
            DeploymentOptions zone_options = new DeploymentOptions();
            zone_options.setConfig(zone_conf);
            //debug debug println statement should be removed
            System.out.println("ZoneManager starting service zone."+zone_id+" with "+zone_conf.toString());

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

            // rebroadcast all ZONE_COMPLETION messages from this Zone to ZONE_ADDRESS
            eb.consumer(ZONE_ADDRESS_LOCAL, msg -> {
                    JsonObject msg_body = new JsonObject(msg.body().toString());
                    if (msg_body.getString("msg_type").equals(Constants.ZONE_COMPLETION))
                        {
                            eb.publish(ZONE_ADDRESS, msg_body);
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
                System.err.println("ZoneManager: no module.name in config()");
                return false;
            }
        
        MODULE_ID = config().getString("module.id"); // A, B, ...
        if (MODULE_ID==null)
            {
                System.err.println("ZoneManager: no module.id in config()");
                return false;
            }

        EB_SYSTEM_STATUS = config().getString("eb.system_status");
        if (EB_SYSTEM_STATUS==null)
            {
                System.err.println("ZoneManager: no eb.system_status in config()");
                return false;
            }

        EB_MANAGER = config().getString("eb.manager");
        if (EB_MANAGER==null)
            {
                System.err.println("ZoneManager: no eb.manager in config()");
                return false;
            }

        //debug test for bad config

        START_ZONES = new ArrayList<String>();
        JsonArray zone_list = config().getJsonArray(MODULE_NAME+".start");
        for (int i=0; i<zone_list.size(); i++)
            {
                START_ZONES.add(zone_list.getString(i));
            }

        ZONE_ADDRESS = config().getString(MODULE_NAME+".zone.address");
        if (ZONE_ADDRESS==null)
            {
                System.err.println("ZoneManager: no "+MODULE_NAME+".zone.address in config()");
                return false;
            }

        ZONE_FEED = config().getString(MODULE_NAME+".zone.feed");
        if (ZONE_FEED==null)
            {
                System.err.println("ZoneManager: no "+MODULE_NAME+".zone.feed in config()");
                return false;
            }
        
        return true;
    }
    
} // end class ZoneManager
