package uk.ac.cam.tfc_server.appmanager;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// AppManager.java
// Version 0.02
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' next-generation Realtime Intelligent Traffic Analysis system
//
// Uses config to launch app verticles into current JVM
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

public class AppManager extends AbstractVerticle {

    // config() vars
    private String EB_SYSTEM_STATUS; // from config()
    private String EB_MANAGER; // from config()
    private String MODULE_NAME; // from config()
    private String MODULE_ID; // from config()
    private ArrayList<String> START_SERVICES; // from config()
    private int    LOG_LEVEL;

    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 15;
    private final int SYSTEM_STATUS_RED_SECONDS = 25;

    private Log logger;

    private EventBus eb = null;

    @Override
    public void start() throws Exception
    {
    // Get src/main/conf/tfc_server.conf config values for module
    if (!get_config())
        {
            System.err.println("AppManager: problem loading config");
            vertx.close();
            return;
        }

    logger = new Log(LOG_LEVEL);

    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+": config()=");
    logger.log(Constants.LOG_DEBUG, config().toString());

    logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": started" );

    eb = vertx.eventBus();

    // iterate through all the Vertx services to be started
    for (int i=0; i<START_SERVICES.size(); i++)
        {
            // get service_name for this vertx service
            final String service_name = START_SERVICES.get(i);

            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                       ": starting service "+service_name);

            vertx.deployVerticle("service:"+service_name,
                                 //deployment_options,
                                 res -> {
                    if (res.succeeded()) {
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                   ": Vertx service "+service_name+ "started");
                    } else {
                        System.err.println(MODULE_NAME+"."+MODULE_ID+
                                           ": failed to start Vertx service " + service_name);
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

    // Load initialization global constants defining this AppManager from config()
    private boolean get_config()
    {
        // config() values needed by all TFC modules are:
        //   tfc.module_id - unique module reference to be used by this verticle
        //   eb.system_status - String eventbus address for system status messages

        MODULE_NAME = config().getString("module.name"); // "AppManager"
        if (MODULE_NAME==null)
            {
                System.err.println("AppManager: no module.name in config()");
                return false;
            }

        MODULE_ID = config().getString("module.id"); // A, B, ...
        if (MODULE_ID==null)
            {
                System.err.println("AppManager: no module.id in config()");
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

        //debug test for bad START_SERVICES config

        START_SERVICES = new ArrayList<String>();
        JsonArray service_list = config().getJsonArray(MODULE_NAME+".start");
        for (int i=0; i<service_list.size(); i++)
            {
                START_SERVICES.add(service_list.getString(i));
            }

        return true;
    }

} // end class AppManager
