package uk.ac.cam.tfc_server.msgrouter;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// MsgRouter.java
//
// Author: Ian Lewis ijl20@cam.ac.uk
//
// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

import java.io.*;
import java.util.ArrayList;

// other tfc_server classes
import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;

public class MsgRouter extends AbstractVerticle {

    private final String VERSION = "0.02";
    
    // from config()
    public int LOG_LEVEL;             // optional in config(), defaults to Constants.LOG_INFO
    private String MODULE_NAME;       // config module.name - normally "msgrouter"
    private String MODULE_ID;         // config module.id - unique for this verticle
    private String EB_SYSTEM_STATUS;  // config eb.system_status
    private String EB_MANAGER;        // config eb.manager
    
    private ArrayList<RouterConfig> START_ROUTERS; // config msgrouters.routers parameters
    
    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 25;
    private final int SYSTEM_STATUS_RED_SECONDS = 35;

    private EventBus eb = null;
    private Log logger;
        
  @Override
  public void start(Future<Void> fut) throws Exception {
      
    // load initialization values from config()
    if (!get_config())
          {
              Log.log_err("MsgRouter."+ MODULE_ID + ": failed to load initial config()");
              vertx.close();
              return;
          }

    System.out.println("debug: LOG_LEVEL "+LOG_LEVEL);
    logger = new Log(LOG_LEVEL);
    
    logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": Version "+VERSION+" started");


    eb = vertx.eventBus();

    // iterate through all the routers to be started
    for (int i=0; i<START_ROUTERS.size(); i++)
        {
            start_router(START_ROUTERS.get(i));
        }

    // send system status message from this module (i.e. to itself) immediately on startup, then periodically
    send_status();
      
    // send periodic "system_status" messages
    vertx.setPeriodic(SYSTEM_STATUS_PERIOD, id -> { send_status();  });

  } // end start()

    // send UP status to the EventBus
    private void send_status()
    {
        eb.publish(EB_SYSTEM_STATUS,
                 "{ \"module_name\": \""+MODULE_NAME+"\"," +
                   "\"module_id\": \""+MODULE_ID+"\"," +
                   "\"status\": \"UP\"," +
                   "\"status_msg\": \"UP\"," +
                   "\"status_amber_seconds\": "+String.valueOf( SYSTEM_STATUS_AMBER_SECONDS ) + "," +
                   "\"status_red_seconds\": "+String.valueOf( SYSTEM_STATUS_RED_SECONDS ) +
                 "}" );
    }
    
    // ************************************************************
    // start_router()
    // start a Router by registering a consumer to the given address
    // ************************************************************
    private void start_router(RouterConfig router_config)
    {
        String router_filter;
        if (router_config.source_filter == null)
            {
                router_filter = "";
            }
        else
            {
                router_filter = " with " + router_config.source_filter.toString();
            }
        System.out.println("MsgRouter."+MODULE_ID+": starting router "+router_config.source_address+ router_filter);

        RouterUtils router_utils = new RouterUtils(vertx, router_config);
        
        // register to router_config.source_address,
        // test messages with router_config.source_filter
        // and call store_msg if current message passes filter
        eb.consumer(router_config.source_address, message -> {
            //System.out.println("MsgRouter."+MODULE_ID+": got message from " + router_config.source_address);
            JsonObject msg = new JsonObject(message.body().toString());
            
            //System.out.println(msg.toString());

            if (router_config.source_filter != null && router_config.source_filter.match(msg))
            {
                // store this message if it matches the filter within the RouterConfig
                router_utils.route_msg(msg);
                return;
            }
            else
            {
                System.out.println("debug: skipping"+LOG_LEVEL);
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                     ": msg skipped no match "+router_config.source_filter.toString());
            }

        });
    
    } // end start_router

    
    //**************************************************************************
    //**************************************************************************
    // Load initialization global constants defining this MsgRouter from config()
    //**************************************************************************
    //**************************************************************************
    private boolean get_config()
    {
        // config() values needed by all TFC modules are:
        //   module.name - usually "msgrouter"
        //   module.id - unique module reference to be used by this verticle
        //   eb.system_status - String eventbus address for system status messages
        //   eb.manager - eventbus address for manager messages
        
        MODULE_NAME = config().getString("module.name");
        if (MODULE_NAME == null)
        {
          Log.log_err("MsgRouter: module.name config() not set");
          return false;
        }
        
        MODULE_ID = config().getString("module.id");
        if (MODULE_ID == null)
        {
          Log.log_err("MsgRouter: module.id config() not set");
          return false;
        }

        LOG_LEVEL = config().getInteger(MODULE_NAME+".log_level", 0);
        System.out.println("debug: config LOG_LEVEL "+LOG_LEVEL);
        if (LOG_LEVEL==0)
            {
                LOG_LEVEL = Constants.LOG_INFO;
            }
        
        EB_SYSTEM_STATUS = config().getString("eb.system_status");
        if (EB_SYSTEM_STATUS == null)
        {
          Log.log_err("MsgRouter."+MODULE_ID+": eb.system_status config() not set");
          return false;
        }

        EB_MANAGER = config().getString("eb.manager");
        if (EB_MANAGER == null)
        {
          Log.log_err("MsgRouter."+MODULE_ID+": eb.manager config() not set");
          return false;
        }

        // iterate through the msgrouter.routers config values
        START_ROUTERS = new ArrayList<RouterConfig>();
        JsonArray config_router_list = config().getJsonArray(MODULE_NAME+".routers");
        for (int i=0; i<config_router_list.size(); i++)
            {
                JsonObject config_json = config_router_list.getJsonObject(i);

                // add MODULE_NAME, MODULE_ID to every RouterConfig
                config_json.put("module_name", MODULE_NAME);
                config_json.put("module_id", MODULE_ID);
                
                RouterConfig router_config = new RouterConfig(config_json);
                
                START_ROUTERS.add(router_config);
            }

        return true;
    } // end get_config()

} // end class MsgRouter

