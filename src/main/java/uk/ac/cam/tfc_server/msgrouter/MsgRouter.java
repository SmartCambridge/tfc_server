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

import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpClientRequest;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;

// other tfc_server classes
import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;

public class MsgRouter extends AbstractVerticle {

    private final String VERSION = "0.03";
    
    // from config()
    public int LOG_LEVEL;             // optional in config(), defaults to Constants.LOG_INFO
    private String MODULE_NAME;       // config module.name - normally "msgrouter"
    private String MODULE_ID;         // config module.id - unique for this verticle
    private String EB_SYSTEM_STATUS;  // config eb.system_status
    private String EB_MANAGER;        // config eb.manager
    
    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 25;
    private final int SYSTEM_STATUS_RED_SECONDS = 35;

    private EventBus eb = null;
    private Log logger;
        
    private ArrayList<JsonObject> START_ROUTERS; // config msgrouters.routers parameters

    // global vars
    private HashMap<String,HttpClient> http_clients; // used to store a HttpClient for each feed_id
    
  @Override
  public void start(Future<Void> fut) throws Exception {
      
    // load initialization values from config()
    if (!get_config())
          {
              Log.log_err("MsgRouter."+ MODULE_ID + ": failed to load initial config()");
              vertx.close();
              return;
          }

    logger = new Log(LOG_LEVEL);
    
    logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": Version "+VERSION+" started with log_level "+LOG_LEVEL);

    eb = vertx.eventBus();

    // create holder for HttpClients, one per router
    http_clients = new HashMap<String,HttpClient>();

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
    private void start_router(JsonObject router_config)
    {
        JsonObject filter_json = router_config.getJsonObject("source_filter");
        boolean has_filter =  filter_json != null;

        final RouterFilter source_filter = has_filter ? new RouterFilter(filter_json) : null;

        final HttpClient http_client = vertx.createHttpClient( new HttpClientOptions()
                                                       .setSsl(router_config.getBoolean("http.ssl"))
                                                       .setTrustAll(true)
                                                       .setDefaultPort(router_config.getInteger("http.port"))
                                                       .setDefaultHost(router_config.getString("http.host"))
                                                             );
        String router_filter_text;
        if (has_filter)
            {
                //source_filter = new RouterFilter(filter_json);  
                router_filter_text = " with " + filter_json.toString();
            }
        else
            {
                router_filter_text = "";
            }
        logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+
                   ": starting router "+router_config.getString("source_address")+ router_filter_text);

        //RouterUtils router_utils = new RouterUtils(vertx, router_config);
        
        // register to router_config.source_address,
        // test messages with router_config.source_filter
        // and call store_msg if current message passes filter
        eb.consumer(router_config.getString("source_address"), message -> {
            //System.out.println("MsgRouter."+MODULE_ID+": got message from " + router_config.source_address);
            JsonObject msg = new JsonObject(message.body().toString());
            
            //System.out.println(msg.toString());

            if (!has_filter || source_filter.match(msg))
            {
                // route this message if it matches the filter within the RouterConfig
                route_msg(http_client, router_config, msg);
                return;
            }
            else
            {
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                     ": msg skipped no match "+router_config.getJsonObject("source_filter").toString());
            }

        });
    
    } // end start_router

    //**************************************************************************
    //**************************************************************************
    // Route the message onwards via POST to destination in config
    //**************************************************************************
    //**************************************************************************
    private void route_msg(HttpClient http_client, JsonObject router_config, JsonObject msg)
    {
        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                   ": routing " + msg);

        // Sample router_config
        //        { 
        //            "source_address": "tfc.everynet_feed.test",
        //            "source_filter": { 
        //                                 "field": "dev_eui",
        //                                 "compare": "=",
        //                                 "value": "0018b2000000113e"
        //                             },
        //             "http.host":  "localhost",              
        //             "http.uri" :  "/everynet_feed/test/adeunis_test2",
        //             "http.ssl":   false,
        //             "http.port":  8098,
        //             "http.post":  true,
        //             "http.token": "cam-auth-test"
        //        }

        String http_uri = router_config.getString("http.uri");

        HttpClientRequest request = http_client.post(http_uri, response -> {
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                     ": msg posted to " + http_uri);

                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                     ": response was " + response.statusCode());

            });
        // Now do stuff with the request
        request.putHeader("content-type", "application/json");
        request.timeout(15000);

        String auth_token = router_config.getString("http.token");
        if (auth_token != null)
        {
            request.putHeader("X-Auth-Token", auth_token);
        }
        // Make sure the request is ended when you're done with it
        request.end(msg.getJsonArray("request_data").getJsonObject(0).toString());
    }

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
        START_ROUTERS = new ArrayList<JsonObject>();
        JsonArray config_router_list = config().getJsonArray(MODULE_NAME+".routers");
        for (int i=0; i<config_router_list.size(); i++)
            {
                JsonObject config_json = config_router_list.getJsonObject(i);

                // add MODULE_NAME, MODULE_ID to every RouterConfig
                config_json.put("module_name", MODULE_NAME);
                config_json.put("module_id", MODULE_ID);
                
                //RouterConfig router_config = new RouterConfig(config_json);
                
                START_ROUTERS.add(config_json);
            }

        return true;
    } // end get_config()

} // end class MsgRouter

