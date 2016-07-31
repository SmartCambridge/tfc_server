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
import uk.ac.cam.tfc_server.util.Log;

public class ZoneManager extends AbstractVerticle {

    // config() vars
    private String EB_SYSTEM_STATUS; // from config()
    private String EB_MANAGER; // from config()
    private String MODULE_NAME; // from config()
    private String MODULE_ID; // from config()
    private ArrayList<String> START_ZONES; // from config()
    private int    LOG_LEVEL;
    
    private String ZONE_ADDRESS; // from config() - address for Zones to publish to
    private String ZONE_FEED; // from config() - address for Zones to subscribe to
    
    //debug get ZONE_NAME from Rita
    private final String ZONE_NAME = "zone"; 
    
    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 15;
    private final int SYSTEM_STATUS_RED_SECONDS = 25;

    private Log logger;
    
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

    logger = new Log(LOG_LEVEL);
    
    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+": config()=");
    logger.log(Constants.LOG_DEBUG, config().toString());

    logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": started" );

    eb = vertx.eventBus();

    //debug -- zone.address and zone.feed should come from manager messages
    JsonObject zone_conf = new JsonObject();
    
    zone_conf.put("module.name", ZONE_NAME);
    // All zones will use this address to transmit 'status up' messages
    zone_conf.put("eb.system_status", EB_SYSTEM_STATUS);
    // All zones will use this address to exchange management/control messages
    zone_conf.put("eb.manager", EB_MANAGER);
    // All zones will subscribe to this address to get vehicle position messages
    zone_conf.put(ZONE_NAME+".feed", ZONE_FEED);

    zone_conf.put(ZONE_NAME+".log_level", LOG_LEVEL);

    // iterate through all the zones to be started
    for (int i=0; i<START_ZONES.size(); i++)
        {
            // get zone_id for this zone
            final String zone_id = START_ZONES.get(i);

            zone_conf.put("module.id", zone_id);
            
            // Each zone has a unique 'local' eventbus address which will be used for
            // just this zone to send all its messages (e.g. vehicle entered, exitted, completed)
            String ZONE_ADDRESS_LOCAL = ZONE_ADDRESS+"."+zone_id;
            zone_conf.put("zone.address", ZONE_ADDRESS_LOCAL);
                          
            DeploymentOptions zone_options = new DeploymentOptions();
            zone_options.setConfig(zone_conf);

            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                       ": starting service zone."+zone_id+" with "+zone_conf.toString());

            vertx.deployVerticle("service:uk.ac.cam.tfc_server.zone."+zone_id,
                                 zone_options,
                                 res -> {
                    if (res.succeeded()) {
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                   ": Zone "+zone_id+ "started");
                    } else {
                        System.err.println(MODULE_NAME+"."+MODULE_ID+
                                           ": failed to start Zone " + zone_id);
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
        
        LOG_LEVEL = config().getInteger(MODULE_NAME+".log_level", 0);
        if (LOG_LEVEL==0)
            {
                LOG_LEVEL = Constants.LOG_INFO;
            }
        
        EB_SYSTEM_STATUS = config().getString("eb.system_status");
        if (EB_SYSTEM_STATUS==null)
            {
                System.err.println(MODULE_NAME+"."+MODULE_ID+
                                   ": no eb.system_status in config()");
                return false;
            }

        EB_MANAGER = config().getString("eb.manager");
        if (EB_MANAGER==null)
            {
                System.err.println(MODULE_NAME+"."+MODULE_ID+
                                   ": no eb.manager in config()");
                return false;
            }

        //debug test for bad START_ZONES config

        START_ZONES = new ArrayList<String>();
        JsonArray zone_list = config().getJsonArray(MODULE_NAME+".start");
        for (int i=0; i<zone_list.size(); i++)
            {
                START_ZONES.add(zone_list.getString(i));
            }

        ZONE_ADDRESS = config().getString(MODULE_NAME+".zone.address");
        if (ZONE_ADDRESS==null)
            {
                System.err.println(MODULE_NAME+"."+MODULE_ID+
                                   ": no "+MODULE_NAME+".zone.address in config()");
                return false;
            }

        ZONE_FEED = config().getString(MODULE_NAME+".zone.feed");
        if (ZONE_FEED==null)
            {
                System.err.println(MODULE_NAME+"."+MODULE_ID+
                                   ": no "+MODULE_NAME+".zone.feed in config()");
                return false;
            }
        
        return true;
    }
    
} // end class ZoneManager
