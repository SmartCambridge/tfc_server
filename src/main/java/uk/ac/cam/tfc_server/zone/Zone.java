package uk.ac.cam.tfc_server.zone;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// Zone.java
// Version 0.14
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' next-generation Realtime Intelligent Traffic Analysis system
//
// Subscribes to address ZONE_FEED and sends messages to ZONE_ADDRESS
//
// Zone sends the following messages to zone.address:

// When a vehicle completes a transit of the Zone, startline..finishline:
//   { "module_name":  MODULE_NAME,
//     "module_id": MODULE_ID,
//     "msg_type": Constants.ZONE_COMPLETION,
//     "vehicle_id": vehicle_id,
//     "route_id": route_id,
//     "ts": finish_ts, // this is a CALCULATED timestamp of the finishline crossing
//     "ts_delta": start_ts_delta + finish_ts_delta, // 'confidence' factor
//     "duration": duration // Zone transit journey time in seconds
//   }

// When a vehicle exits the Zone other than via start and finish lines
//   { "module_name":  MODULE_NAME,
//     "module_id": MODULE_ID,
//     "msg_type": Constants.ZONE_EXIT,
//     "vehicle_id": vehicle_id,
//     "route_id": route_id,
//     "ts": position_ts // this is the timestamp of the first point OUTSIDE the zone
//     "ts_delta": ts - prev_ts // duration of exit vector (in seconds)
//   }

// When a vehicle enters the Zone via the start line
//   { "module_name":  MODULE_NAME,
//     "module_id": MODULE_ID,
//     "msg_type": Constants.ZONE_START,
//     "vehicle_id": vehicle_id,
//     "route_id": route_id,
//     "ts": start_ts // this is the timestamp of the first point OUTSIDE the zone
//     "ts_delta": ts - prev_ts // duration of entry vector (in seconds)
//   }

// When a vehicle enters the Zone but NOT via the start line
//   { "module_name":  MODULE_NAME,
//     "module_id", MODULE_ID,
//     "msg_type", Constants.ZONE_ENTRY,
//     "vehicle_id", vehicle_id,
//     "route_id", route_id,
//     "ts", position_ts // this is the timestamp of the first point INSIDE the zone
//     "ts_delta": ts - prev_ts // duration of entry vector (in seconds)
//   }

// When a ZONE_UPDATE_REQUEST message is received, Zone sends the history of prior messages
//   { "module_name": MODULE_NAME,
//        "module_id", MODULE_ID),
//        "msg_type", Constants.ZONE_UPDATE,
//        "msgs", [ <zone message>, <zone message> ... ]
//   }

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.Handler;
import io.vertx.core.file.FileSystem;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;

import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.zone.ZoneConfig; // also used by BatcherWorker for synchronous compute
import uk.ac.cam.tfc_server.zone.ZoneCompute;// also used by BatcherWorker for synchronous compute
import uk.ac.cam.tfc_server.zone.Vehicle;
import uk.ac.cam.tfc_server.util.Constants;
import uk.ac.cam.tfc_server.util.Position;

import uk.ac.cam.tfc_server.util.IMsgHandler; // Interface to provide handle_msg routine

// ********************************************************************************************
// ********************************************************************************************
// ********************************************************************************************
// Here is the main Zone class definition
// ********************************************************************************************
// ********************************************************************************************
// ********************************************************************************************

public class Zone extends AbstractVerticle {

    private ZoneConfig zone_config;        // initialized in get_config()

    private String EB_SYSTEM_STATUS;  // vertx config eb.system_status
    private String EB_MANAGER;        // vertx config eb.manager

    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 15; // delay before flagging system as AMBER
    private final int SYSTEM_STATUS_RED_SECONDS = 25; // delay before flagging system as RED

    // Zone globals
    
    private EventBus eb = null;
    private String tfc_data_zone = null;

    private HashMap<String, MsgHandler> msg_handlers;

    private Log logger;
    
  // **************************************************************************************
  // Zone Verticle Startup procedure
  @Override
  public void start(Future<Void> fut) throws Exception {

    // will publish to EventBus address ZONE_ADDRESS e.g. tfc.zone.madingley_road_in

    // load Zone initialization values from config()
    if (!get_config())
          {
              Log.log_err("Zone: failed to load initial config()");
              vertx.close();
              return;
          }
      
    logger = new Log(zone_config.LOG_LEVEL);
    
    logger.log(Constants.LOG_INFO, zone_config.MODULE_NAME+"."+zone_config.MODULE_ID+": started");

    // Initialization from config() complete
    
    eb = vertx.eventBus();

    msg_handlers = new HashMap<String, MsgHandler>();

    // **********  Set up connection to EventBus  ********************************************
    // set up a handler for manager messages

    eb.consumer(EB_MANAGER, eb_message -> {
            JsonObject msg = new JsonObject(eb_message.body().toString());
            logger.log(Constants.LOG_INFO,zone_config.MODULE_NAME+"."+zone_config.MODULE_ID+
                       ": manager msg received "+msg.toString());
            if (msg.getString("to_module_name").equals(zone_config.MODULE_NAME) &&
                msg.getString("to_module_id").equals(zone_config.MODULE_ID))
            {
                manager_msg(msg);
            }
    });

    //... if (zone.feed in config(), then start processing immediately
    String ZONE_ADDRESS = config().getString("zone.address");
    String ZONE_FEED = config().getString("zone.feed");
    if (ZONE_ADDRESS != null && ZONE_FEED != null)
        {
            monitor_feed(ZONE_FEED, ZONE_ADDRESS);
        }

    // send periodic "system_status" messages
    vertx.setPeriodic(SYSTEM_STATUS_PERIOD, id -> {
      //System.out.println("Zone."+zone_config.MODULE_ID+": sending UP status to "+EB_SYSTEM_STATUS);
      eb.publish(EB_SYSTEM_STATUS,
                 "{ \"module_name\": \""+zone_config.MODULE_NAME+"\"," +
                   "\"module_id\": \""+zone_config.MODULE_ID+"\"," +
                   "\"status\": \"UP\"," +
                   "\"status_amber_seconds\": "+String.valueOf( SYSTEM_STATUS_AMBER_SECONDS ) + "," +
                   "\"status_red_seconds\": "+String.valueOf( SYSTEM_STATUS_RED_SECONDS ) +
                 "}" );
      });

  } // end start()

    // Process a manager message to this module
    private void manager_msg(JsonObject msg)
    {
        if (msg.getString("msg_type").equals(Constants.ZONE_SUBSCRIBE))
            {
                handle_subscription(msg);              
            }
        else if (msg.getString("msg_type").equals(Constants.ZONE_UPDATE_REQUEST))
            {
                handle_update_request(msg);
            }
        else if (msg.getString("msg_type").equals(Constants.ZONE_INFO_REQUEST))
            {
                handle_info_request(msg);
            }
    }

    // Zone has received a ZONE_SUBSCRIBE message on the eb.manager eventbus address
    private void handle_subscription(JsonObject request_msg)
    {
        // set up a subscription to a feed
        //debug need a data structure for a list of subscriptions
        String ZONE_FEED = request_msg.getString("zone.feed");
        
        String ZONE_ADDRESS = request_msg.getString("zone.address");

        monitor_feed(ZONE_FEED, ZONE_ADDRESS);
    }        


    // Zone has received a ZONE_UPDATE_REQUEST message on the eb.manager eventbus address
    // so should broadcast a message with all the completion messages for the day so far
    private void handle_update_request(JsonObject request_msg)
    {
        logger.log(Constants.LOG_INFO,zone_config.MODULE_NAME+"."+zone_config.MODULE_ID+
                   ": sending Zone update");
        // ****************************************
        // Send ZONE_UPDATE message to ZONE_ADDRESS
        // ****************************************

        String ZONE_ADDRESS = request_msg.getString("zone.address");

        JsonObject msg = new JsonObject();

        msg.put("module_name", zone_config.MODULE_NAME); // "zone" don't really need this on ZONE_ADDRESS
        msg.put("module_id", zone_config.MODULE_ID);     // e.g. "madingley_road_in"
        msg.put("msg_type", Constants.ZONE_UPDATE);
        msg.put("msgs", msg_handlers.get(ZONE_ADDRESS).get_update());

        // Send zone_completed message to common zone.address
        vertx.eventBus().publish(ZONE_ADDRESS, msg);
    }

    // Zone has received a ZONE_INFO_REQUEST message on the eb.manager eventbus address
    // so should broadcast a message with the zone details
    private void handle_info_request(JsonObject request_msg)
    {
        logger.log(Constants.LOG_INFO,zone_config.MODULE_NAME+"."+zone_config.MODULE_ID+
                   ": sending Zone info");
        // ****************************************
        // Send ZONE_INFO message to ZONE_ADDRESS
        // ****************************************

        String ZONE_ADDRESS = request_msg.getString("zone.address");

        JsonObject msg = new JsonObject();

        msg.put("module_name", zone_config.MODULE_NAME); // "zone" don't really need this on ZONE_ADDRESS
        msg.put("module_id", zone_config.MODULE_ID);     // e.g. "madingley_road_in"
        msg.put("msg_type", Constants.ZONE_INFO);
        msg.put("center", zone_config.CENTER.toJsonObject());
        msg.put("finish_index", zone_config.FINISH_INDEX );
        msg.put("zoom", zone_config.ZOOM);
        JsonArray json_path = new JsonArray();
        for (int i=0; i < zone_config.PATH.size(); i++)
            {
                json_path.add(zone_config.PATH.get(i).toJsonObject());
            }
        msg.put("path", json_path);
        
        // Send zone_completed message to common zone.address
        vertx.eventBus().publish(ZONE_ADDRESS, msg);
    }

    // Subscribe to ZONE_FEED position messages, and publish zone messages to ZONE_ADDRESS.
    // Called in start()
    private void monitor_feed(String ZONE_FEED, String ZONE_ADDRESS)
    {
      logger.log(Constants.LOG_INFO,zone_config.MODULE_NAME+"."+zone_config.MODULE_ID+
                 ": subscribing to "+ ZONE_FEED);

      // Create new MsgHandler if not already existing for this ZONE_ADDRESS
      if (!msg_handlers.containsKey(ZONE_ADDRESS))
          {
              MsgHandler mh = new MsgHandler(ZONE_ADDRESS);

              // set up ZoneCompute object
              ZoneCompute zc = new ZoneCompute(zone_config, mh);

              msg_handlers.put(ZONE_ADDRESS, mh);

              // set up a handler for the actual vehicle position feed messages
              vertx.eventBus().consumer(ZONE_FEED, eb_message -> {

                  JsonObject feed_message = new JsonObject(eb_message.body().toString());

                  zc.handle_feed(feed_message);
              });
          }
    }

    //*************************************************************************************
    // Class MsgBuffer
    //*************************************************************************************
    
    // Circular buffer to hold completion messages since start of day
    // Initially buffer fills with elements from buffer[0], buffer[1] etc
    // and when buffer[SIZE] would be reached 'full' is set to 'true' and the write index is
    // reset to zero.
    // So ordered complete set of entries are:
    // full==false: buffer[0]..buffer[write_buffer-1]
    // full==true:  buffer[write_buffer].. loop around end to buffer[write_buffer-1]
    class MsgBuffer {
        int SIZE;
        JsonArray buffer;

        // initialize the object
        public MsgBuffer(int max_size)
        {
            SIZE = max_size;
            buffer = new JsonArray();
        }

        // add a msg to the buffer
        public void add(JsonObject msg)
        {
            //remove the first element if we've reached max size
            if (buffer.size() == SIZE)
                {
                    buffer.remove(0);
                }
            buffer.add(msg);
        }

        // reset the buffer to empty
        public void clear()
        {
            buffer.clear();
        }

        // return the number of messages stored in the buffer
        public int size()
        {
            return buffer.size();
        }

        // return the entire buffer as a JsonArray in the correct order
        public JsonArray json_array()
        {
            return buffer;
        }
        
    } // end class MsgBuffer

    //*************************************************************************************
    // Class MsgHandler
    //*************************************************************************************
    //
    // passed to ZoneCompute for callback to handle zone event messages
    //
    // MsgHandler is initialized with the eventbus address than messages should be
    // broadcast to.
    //
    class MsgHandler implements IMsgHandler {

        String ZONE_ADDRESS;

        MsgBuffer msg_buffer;

        MsgHandler(String s)
        {
            ZONE_ADDRESS = s;
            
            msg_buffer = new MsgBuffer(Constants.ZONE_BUFFER_SIZE);
            
        }
        
        // general handle_msg function, called by ZoneCompute
        public void handle_msg(JsonObject msg)
        {
            if (msg.getString("msg_type").equals(Constants.ZONE_COMPLETION))
                {
                  // accumulate this Completion message in the ring buffer
                  msg_buffer.add(msg);
                }
            //System.out.println("Zone handle_msg called with " + address);
            vertx.eventBus().publish(ZONE_ADDRESS, msg);
        }

        // Zone has received a ZONE_UPDATE_REQUEST message on the eb.manager eventbus address
        // so return a JsonArray containing current message cache
        public JsonArray get_update()
        {
            return msg_buffer.json_array();
        }

    } // end class MsgHandler
    
    // Load initialization global constants defining this Zone from config()
    private boolean get_config()
    {
        // config() values needed by all TFC modules are:
        //   module.name - usually "zone"
        //   module.id - unique module reference to be used by this verticle
        //   eb.system_status - String eventbus address for system status messages
        //   eb.manager - eventbus address for manager messages
        
        // Expected config() values defining this Zone are:
        //   zone.feed (optional) address to subscribe for position feed
        //   zone.address (optional) address for publishing zone update messages
        //   zone.name - String
        //   zone.id   - String
        //   zone.path - Position[]
        //   zone.center - Position
        //   zone.zoom - int
        //   zone.finish_index - int

        zone_config = new ZoneConfig(config());
        
        EB_SYSTEM_STATUS = config().getString("eb.system_status");
        if (EB_SYSTEM_STATUS==null)
            {
                Log.log_err("Zone."+zone_config.MODULE_ID+": no eb.system_status in config()");
                return false;
            }

        EB_MANAGER = config().getString("eb.manager");
        if (EB_MANAGER==null)
            {
                Log.log_err("Zone."+zone_config.MODULE_ID+": no eb.manager in config()");
                return false;
            }
        
        return zone_config.valid;
    }
        
} // end class Zone
