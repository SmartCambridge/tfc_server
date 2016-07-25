package uk.ac.cam.tfc_server.zone;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// Zone.java
// Version 0.11
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
import java.util.HashMap;
import java.util.ArrayList;

// time/date crapola
import java.util.Date;
import java.text.SimpleDateFormat; // for timestamp conversion to HH:MM:SS
import java.time.LocalTime; // for timestamp duration conversion to HH:mm:ss
import java.util.TimeZone;

import uk.ac.cam.tfc_server.util.Position;
import uk.ac.cam.tfc_server.util.Constants;
import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.zone.ZoneConfig;

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

    private Box box;
    
    //private final String ENV_VAR_ZONE_PATH = "TFC_DATA_ZONE"; // Linux var containing filepath root for csv files

    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 15; // delay before flagging system as AMBER
    private final int SYSTEM_STATUS_RED_SECONDS = 25; // delay before flagging system as RED

    // Zone globals
    
    private EventBus eb = null;
    private String tfc_data_zone = null;
    //debug need a vehicles data hashmap for each monitoring instance
    private HashMap<String, Vehicle> vehicles; // dictionary to store vehicle status updated from feed
    // zone_msg_buffer has a MsgBuffer entry for each zone.address
    private HashMap<String, MsgBuffer> zone_msg_buffer; // stores zone completion messages since start of day

    public Zone(ZoneConfig zone_config)
    {
        System.out.println(zone_config.MODULE_NAME+"."+zone_config.MODULE_ID+" Zone object created");
    }
    
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
      
    System.out.println("Zone."+zone_config.MODULE_ID+": started");

    // create box object with boundaries of rectangle that includes this zone polygon
    box = new Box();

    zone_msg_buffer = new HashMap<String, MsgBuffer>();
    
    // Initialization from config() complete
    
    eb = vertx.eventBus();

    // **********  Define the data structure for updated vehicle data  ***********************

    vehicles = new HashMap<String, Vehicle>();


    // **********  Set up connection to EventBus  ********************************************
    // set up a handler for manager messages

    eb.consumer(EB_MANAGER, eb_message -> {
            JsonObject msg = new JsonObject(eb_message.body().toString());
            System.out.println("Zone."+zone_config.MODULE_ID+": manager msg received "+msg.toString());
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
            System.out.println("Zone."+zone_config.MODULE_ID+": sending UP status to "+EB_SYSTEM_STATUS);
      eb.publish(EB_SYSTEM_STATUS,
                 "{ \"module_name\": \""+zone_config.MODULE_NAME+"\"," +
                   "\"module_id\": \""+zone_config.MODULE_ID+"\"," +
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

        zone_config = new ZoneConfig();
        
        zone_config.MODULE_NAME = config().getString("module.name"); // "zonemanager"
        if (zone_config.MODULE_NAME==null)
            {
                Log.log_err("Zone: no module.name in config()");
                return false;
            }
        
        zone_config.MODULE_ID = config().getString("module.id"); // A, B, ...
        if (zone_config.MODULE_ID==null)
            {
                Log.log_err("Zone: no module.id in config()");
                return false;
            }

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

        zone_config.ZONE_NAME = config().getString("zone.name");

        zone_config.PATH = new ArrayList<Position>();
        JsonArray json_path = config().getJsonArray("zone.path", new JsonArray());
        for (int i=0; i < json_path.size(); i++) {
            zone_config.PATH.add(new Position(json_path.getJsonObject(i)));
        }

        zone_config.CENTER = new Position(config().getJsonObject("zone.center"));
        
        zone_config.ZOOM = config().getInteger("zone.zoom");
        
        zone_config.FINISH_INDEX = config().getInteger("zone.finish_index");

        //System.out.println("Zone: "+zone_config.MODULE_NAME+"."+zone_config.MODULE_ID+" get_config(): ZONE_NAME is "+String.valueOf(ZONE_NAME));
        
        return true;
    }
    
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
        System.out.println("Zone."+zone_config.MODULE_ID+": sending Zone update");
        // ****************************************
        // Send ZONE_UPDATE message to ZONE_ADDRESS
        // ****************************************

        String ZONE_ADDRESS = request_msg.getString("zone.address");

        JsonObject msg = new JsonObject();

        msg.put("module_name", zone_config.MODULE_NAME); // "zone" don't really need this on ZONE_ADDRESS
        msg.put("module_id", zone_config.MODULE_ID);     // e.g. "madingley_road_in"
        msg.put("msg_type", Constants.ZONE_UPDATE);
        msg.put("msgs", zone_msg_buffer.get(ZONE_ADDRESS).json_array());

        // Send zone_completed message to common zone.address
        vertx.eventBus().publish(ZONE_ADDRESS, msg);
    }

    // Zone has received a ZONE_INFO_REQUEST message on the eb.manager eventbus address
    // so should broadcast a message with the zone details
    private void handle_info_request(JsonObject request_msg)
    {
        System.out.println("Zone."+zone_config.MODULE_ID+": sending Zone info");
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
              System.out.println("Zone: " + zone_config.MODULE_NAME + "." + zone_config.MODULE_ID +
                                 " subscribing to "+ ZONE_FEED);

              // Create new MsgBuffer if not already existing for this ZONE_ADDRESS
              if (!zone_msg_buffer.containsKey(ZONE_ADDRESS))
                  {
                      zone_msg_buffer.put(ZONE_ADDRESS, new MsgBuffer(Constants.ZONE_BUFFER_SIZE));
                  }               
              // set up a handler for the actual vehicle position feed messages
              vertx.eventBus().consumer(ZONE_FEED, eb_message -> {

                  JsonObject feed_message = new JsonObject(eb_message.body().toString());

                  handle_feed(feed_message, ZONE_ADDRESS);
              });
    }

    // return true if Position p is INSIDE the Zone
    // http://stackoverflow.com/questions/13950062/checking-if-a-longitude-latitude-coordinate-resides-inside-a-complex-polygon-in
    public boolean inside(Position p) {
        // easy optimization - return false if position is outside bounding rectangle (box)
        if (p.lat > box.north || p.lat < box.south || p.lng < box.west || p.lng > box.east)
        return false;

        Position lastPoint = zone_config.PATH.get(zone_config.PATH.size() - 1);
        boolean isInside = false;
        double x = p.lng;
        for (int i=0; i<zone_config.PATH.size(); i++)
        {
            Position point = zone_config.PATH.get(i);
            double x1 = lastPoint.lng;
            double x2 = point.lng;
            double dx = x2 - x1;

            if (Math.abs(dx) > 180.0)
            {
                // we have, most likely, just jumped the dateline.  Normalise the numbers.
                if (x > 0)
                {
                    while (x1 < 0)
                    x1 += 360;
                    while (x2 < 0)
                    x2 += 360;
                }
                else
                {
                    while (x1 > 0)
                    x1 -= 360;
                    while (x2 > 0)
                    x2 -= 360;
                }
                dx = x2 - x1;
            }

            if ((x1 <= x && x2 > x) || (x1 >= x && x2 < x))
            {
                double grad = (point.lat - lastPoint.lat) / dx;
                double intersectAtLat = lastPoint.lat + ((x - x1) * grad);

                if (intersectAtLat > p.lat)
                isInside = !isInside;
            }
            lastPoint = point;
        }

        return isInside;
    }

    // return a 'startline' Intersect
    // .success = true if vehicle crossed startline between v.prev_position & v.position
    // .position = lat, lnt, ts of point of intersection
    public Intersect start_line(Vehicle v)
    {
        return intersect(0,v);
    }

    // as above, for finish line
    public Intersect finish_line(Vehicle v)
    {
        return intersect(zone_config.FINISH_INDEX, v);
    }
    
    // http://stackoverflow.com/questions/563198/how-do-you-detect-where-two-line-segments-intersect
    // Detect whether lines A->B and C->D intersect
    // return { intersect: true/false, position: LatLng (if lines do intersect), progress: 0..1 }
    // where 'progress' is how far the intersection is along the A->B path

    public Intersect intersect(int path_index, Vehicle v)
    {
        Intersect i = new Intersect();

        Position A = v.prev_position;
        Position B = v.position;

        Position C = zone_config.PATH.get(path_index);
        Position D = zone_config.PATH.get(path_index+1);

        double s1_lat = B.lat - A.lat;
        double s1_lng = B.lng - A.lng;
        double s2_lat = D.lat - C.lat;
        double s2_lng = D.lng - C.lng;
       
        double s = (-s1_lat * (A.lng - C.lng) + s1_lng * (A.lat - C.lat)) / (-s2_lng * s1_lat + s1_lng * s2_lat);
        double progress = ( s2_lng * (A.lat - C.lat) - s2_lat * (A.lng - C.lng)) / (-s2_lng * s1_lat + s1_lng * s2_lat);

        if (s >= 0 && s <= 1 && progress >= 0 && progress <= 1)
            {
                // lines A->B and C->D intersect
                i.success = true;
                i.position = new Position( A.lat + (progress * s1_lat), A.lng + (progress * s1_lng) );
                i.position.ts = v.prev_position.ts + (Long) Math.round((v.position.ts - v.prev_position.ts) * progress);

                //System.out.println("entry vector ("+A.lat+","+A.lng+")..("+B.lat+","+B.lng+")");
                //System.out.println("start line   ("+C.lat+","+C.lng+")..("+D.lat+","+D.lng+")");
                //System.out.println("progress     "+progress);
                //System.out.println(v.position.ts + ","+v.prev_position.ts+","+progress+","+i.position.ts);
                return i;
            }

        return i; // lines don't intersect
    } // end intersect()
    
// ***************************************************************************************
// **********                            *************************************************
// **********  Here is where we handle   *************************************************
// **********  each feed update          *************************************************
// **********                            *************************************************
// ***************************************************************************************
  
    private void handle_feed(JsonObject feed_message, String ZONE_ADDRESS)
    {
        JsonArray entities = feed_message.getJsonArray("entities");

        String filename = feed_message.getString("filename");
        String filepath = feed_message.getString("filepath");

        //System.out.println("Zone: "+zone_config.MODULE_NAME+"."+zone_config.MODULE_ID+" ("+ String.valueOf(entities.size()) + "): " + filename);

        for (int i = 0; i < entities.size(); i++)
            {
              JsonObject position_record = entities.getJsonObject(i);
              update_vehicle(position_record, ZONE_ADDRESS);
            }

        //System.out.println("Zone "+ String.valueOf(vehicles.size()) + " records in vehicles HashMap)");
        //debug
        /*

        FileSystem fs = vertx.fileSystem();
        
        Buffer buf = Buffer.buffer();

        // add csv header to buf
        //buf.appendString(CSV_FILE_HEADER+"\n");
        
        // Write file to $TFC_DATA_CSV
        //
        // if full directory path exists, then write file
        // otherwise create full path first
        final String csv_path = tfc_data_zone+"/"+filepath;
        System.out.println("Writing "+csv_path+"/"+filename+".csv");
        fs.exists(csv_path, result -> {
            if (result.succeeded() && result.result())
                {
                    System.out.println("FeedCSV: path "+csv_path+" exists");
                    write_file(fs, buf, csv_path+"/"+filename+".csv");
                }
            else
                {
                    System.out.println("FeedCSV: Creating directory "+csv_path);
                    fs.mkdirs(csv_path, mkdirs_result -> {
                            if (mkdirs_result.succeeded())
                                {
                                    write_file(fs, buf, csv_path+"/"+filename+".csv");
                                }
                            else
                                {
                                    Log.log_err("FeedCSV error creating path "+csv_path);
                                }
                        });
                }
        });
        */
    }

    // Update the vehicles[vehicle_id] record with this feed entry
    private void update_vehicle(JsonObject position_record, String ZONE_ADDRESS)
    {
      String vehicle_id = position_record.getString("vehicle_id");
      Vehicle v = vehicles.get(vehicle_id);
      if (v == null)
          {
              v = new Vehicle(position_record);
              v.within = inside(v.position);
              vehicles.put(vehicle_id, v);
              return; // This is first position record for this vehicle, so just initialize entry
          }

      // These is existing position record for this vehicle, so update with the latest attributes from feed
      v.update(position_record);
      // And set the flag for whether this vehicle is within this Zone
      v.within = inside(v.position);

      //****************************************************************************************************
      //*************************  This vehicle data is all ready, so do Zone enter/exit logic  ************
      //****************************************************************************************************

      // DID VEHICLE ENTER? either via the startline (zone_start) or into the zone some other way (zone_entry)
      if (v.within && !v.prev_within)
          {
              // Did vehicle cross start line?
              Intersect i = start_line(v);
              if (i.success)
                  {
                      //debug - we need to set a confidence factor on start/finish times

                      // Set start timestamp to timestamp at Intersection with startline
                      v.start_ts = i.position.ts;
                      // calculate 'time delta' within which this start time was calculated
                      // i.e. the difference in timestamps between points when vehicle entered zone
                      v.start_ts_delta = v.position.ts - v.prev_position.ts;

                      // ZONE_START (entry via start line)
                      zone_start(ZONE_ADDRESS, v);
                      
                  }
              else
                  {
                      // ZONE_ENTRY (entry but not via start line)
                      zone_entry(ZONE_ADDRESS, v);
                  }
          }
      // IS VEHICLE TRAVELLING WITHIN ZONE?
      else if (v.within && v.prev_within)
          {
              // vehicle is continuing to travel within zone
              //System.out.println("Zone: vehicle_id("+vehicle_id+") inside zone "+ZONE_NAME);
          }
      // HAS VEHICLE EXITTED ZONE? either via the finish line (zone_completion) or not (zone_exit)
      else if (!v.within && v.prev_within)
          {
              // Vehicle has just exitted zone

              // did vehicle cross finish line?
              Intersect i = finish_line(v);
              if (i.success)
                  {
                      Long finish_ts = i.position.ts;
                      
                      // if we also have a good entry, then this is a successful COMPLETION
                      if (v.start_ts>0L)
                        {
                            // ZONE_COMPLETION
                            zone_completion(ZONE_ADDRESS, v, finish_ts);
                        }
                      else
                        {
                            // ZONE_EXIT via finish line but no prior good start
                            zone_finish_no_start(ZONE_ADDRESS, v, finish_ts);
                        }
                  }
              else
                  {
                      // ZONE EXIT but not via finish line
                      zone_exit(ZONE_ADDRESS, v);
                  }
              
              // Reset the Zone start time for this vehicle
              v.start_ts = 0L;
              v.start_ts_delta = 0L;
          }
    }

    // ******************************************************************************************
    // ******************************************************************************************
    // ************* Handle each Zone event for current vehicle  ********************************
    // ************* i.e. ZONE_START, ZONE_COMPLETION, ZONE_EXIT ********************************
    // ******************************************************************************************
    // ******************************************************************************************

    private void zone_start(String ZONE_ADDRESS, Vehicle v)
    {
      System.out.println( "Zone: ,"+zone_config.MODULE_ID+",vehicle_id("+v.vehicle_id+
                          ") clean start at "+ts_to_time_str(v.start_ts) +
                          " start_ts_delta " + v.start_ts_delta);

      // ****************************************
      // Send Zone event message to ZONE_ADDRESS
      // ****************************************

      JsonObject msg = new JsonObject();

      msg.put("module_name", zone_config.MODULE_NAME); // "zone" don't really need this on ZONE_ADDRESS
      msg.put("module_id", zone_config.MODULE_ID);     // e.g. "madingley_road_in"
      msg.put("msg_type", Constants.ZONE_START);
      msg.put("vehicle_id", v.vehicle_id);
      msg.put("route_id", v.route_id);
      msg.put("ts", v.start_ts);
      msg.put("ts_delta", v.start_ts_delta);

      // Send zone_completed message to common zone.address
      vertx.eventBus().publish(ZONE_ADDRESS, msg);
    }

    private void zone_entry(String ZONE_ADDRESS, Vehicle v)
    {
      System.out.println("Zone: ,"+zone_config.MODULE_ID+",vehicle_id("+v.vehicle_id+
                         ") early entry at "+ts_to_time_str(v.position.ts)+
                         " ts_delta " + (v.position.ts - v.prev_position.ts));
      // ****************************************
      // Send Zone event message to ZONE_ADDRESS
      // ****************************************

      JsonObject msg = new JsonObject();

      msg.put("module_name", zone_config.MODULE_NAME); // "zone" don't really need this on ZONE_ADDRESS
      msg.put("module_id", zone_config.MODULE_ID);     // e.g. "madingley_road_in"
      msg.put("msg_type", Constants.ZONE_ENTRY);
      msg.put("vehicle_id", v.vehicle_id);
      msg.put("route_id", v.route_id);
      msg.put("ts", v.position.ts);
      msg.put("ts_delta", v.position.ts - v.prev_position.ts);

      // Send zone_completed message to common zone.address
      vertx.eventBus().publish(ZONE_ADDRESS, msg);
    }
    
    private void zone_completion(String ZONE_ADDRESS, Vehicle v, Long finish_ts)
    {

      // exit completion message
      Long duration = finish_ts - v.start_ts; // time taken to transit this Zone

      // calculate duration of exit vector
      Long finish_ts_delta = v.position.ts - v.prev_position.ts;
      
      // Build console string and output
      // e.g. 2016-03-16 15:19:08,Cam Test,315,no_route,00:00:29,0.58,COMPLETED,15:11:41,15:18:55,00:07:14
      String completed_log = "Zone: ,"+zone_config.MODULE_ID+",";
      completed_log += "COMPLETED,";
      completed_log += v.vehicle_id+",";
      completed_log += v.route_id + ",";
      completed_log += finish_ts+",";
      completed_log += duration+",";
      completed_log += ts_to_datetime_str(v.position.ts) + ",";
      completed_log += ts_to_time_str(v.start_ts) + ",";
      completed_log += ts_to_time_str(finish_ts) + ","; // finish time
      completed_log += duration_to_time_str(v.start_ts_delta) + ",";
      completed_log += duration_to_time_str(finish_ts_delta);

      System.out.println(completed_log);

      // ****************************************
      // Send Zone event message to ZONE_ADDRESS
      // ****************************************

      JsonObject msg = new JsonObject();

      msg.put("module_name", zone_config.MODULE_NAME); // "zone" don't really need this on ZONE_ADDRESS
      msg.put("module_id", zone_config.MODULE_ID);     // e.g. "madingley_road_in"
      msg.put("msg_type", Constants.ZONE_COMPLETION);
      msg.put("vehicle_id", v.vehicle_id);
      msg.put("route_id", v.route_id);
      msg.put("ts", finish_ts);
      msg.put("duration", duration);
      // note we send start_ts_delta + finish_ts_delta as the 'confidence' factor
      msg.put("ts_delta", finish_ts_delta + v.start_ts_delta);

      // accumulate this Completion message in the ring buffer
      zone_msg_buffer.get(ZONE_ADDRESS).add(msg);

      // Send zone_completed message to common zone.address
      vertx.eventBus().publish(ZONE_ADDRESS, msg);
    }
    
    private void zone_finish_no_start(String ZONE_ADDRESS, Vehicle v, Long finish_ts)
    {
      // output clean exit (no start) message
      System.out.println("Zone: ,"+zone_config.MODULE_ID+",vehicle_id("+v.vehicle_id+
                         ") clean exit (no start) at "+ts_to_time_str(finish_ts) +
                         " ts_delta " + (v.position.ts - v.prev_position.ts));
      // ****************************************
      // Send Zone event message to ZONE_ADDRESS
      // ****************************************

      JsonObject msg = new JsonObject();

      msg.put("module_name", zone_config.MODULE_NAME); // "zone" don't really need this on ZONE_ADDRESS
      msg.put("module_id", zone_config.MODULE_ID);     // e.g. "madingley_road_in"
      msg.put("msg_type", Constants.ZONE_EXIT);
      msg.put("vehicle_id", v.vehicle_id);
      msg.put("route_id", v.route_id);
      msg.put("ts", finish_ts);
      msg.put("ts_delta", v.position.ts - v.prev_position.ts);

      // Send zone_completed message to common zone.address
      vertx.eventBus().publish(ZONE_ADDRESS, msg);
    }
    
    private void zone_exit(String ZONE_ADDRESS, Vehicle v)
    {
      System.out.println("Zone: ,"+zone_config.MODULE_ID+",vehicle_id("+v.vehicle_id+
                         ") early exit at "+ts_to_time_str(v.position.ts)+
                         " ts_delta " + (v.position.ts - v.prev_position.ts));
      // ****************************************
      // Send ZONE_EXIT event message to ZONE_ADDRESS
      // ****************************************

      JsonObject msg = new JsonObject();

      msg.put("module_name", zone_config.MODULE_NAME); // "zone" don't really need this on ZONE_ADDRESS
      msg.put("module_id", zone_config.MODULE_ID);     // e.g. "madingley_road_in"
      msg.put("msg_type", Constants.ZONE_EXIT);
      msg.put("vehicle_id", v.vehicle_id);
      msg.put("route_id", v.route_id);
      msg.put("ts", v.position.ts);
      msg.put("ts_delta", v.position.ts - v.prev_position.ts);

      // Send zone_completed message to common zone.address
      vertx.eventBus().publish(ZONE_ADDRESS, msg);
    }
    
    // ******************************************************************************************
    // ****************** Some support functions ************************************************
    // ******************************************************************************************

    //debug I'm sure these should be in a general RITA library...
    private String ts_to_time_str(Long ts)
    {
      Date ts_date = new Date(ts * 1000);
      return (new SimpleDateFormat("HH:mm:ss")).format(ts_date);
    }

    private String ts_to_datetime_str(Long ts)
    {
      Date ts_date = new Date(ts * 1000);
      SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
      fmt.setTimeZone(TimeZone.getTimeZone("GMT"));
      
      return fmt.format(ts_date);
    }

    // convert duration in SECONDS to hh:mm:ss
    private String duration_to_time_str(Long d)
    {
        if (d >= 24 * 60 * 60)
            {
                Log.log_err("Zone: "+zone_config.MODULE_ID+" ERROR duration "+d+" > 24 hours");
            }
        String d_time = LocalTime.ofSecondOfDay(d).toString();

        // d_time is either "HH:mm" or "HH:mm:ss" so pad ":00" if needed
        return d_time.length() == 5 ? d_time + ":00" : d_time ;
    }

  private void write_file(FileSystem fs, Buffer buf, String file_path)
  {
    fs.writeFile(file_path, 
                 buf, 
                 result -> {
      if (result.succeeded()) {
        System.out.println("File "+file_path+" written");
      } else {
        Log.log_err("Zone."+zone_config.MODULE_ID+": write_file error ..." + result.cause());
      }
    });
  } // end write_file

    //*************************************************************************************
    // Class Box - rectangle surrounding zone polygon, for fast 'within zone' exclusion
    //*************************************************************************************
    
    // The Zone Boundary has a simplified boundary of a Box, i.e. a
    // simple rectangle. This permits a fast initial test of
    // whether a Position is outside the Zone. I.e. if
    // a Position is outside the Box, it's outside the Zone.
    class Box {
        double north = -90;
        double south = 90;
        double east = -180;
        double west = 180;

        Box() {
            for (int i=0; i<zone_config.PATH.size(); i++)
            {
                if (zone_config.PATH.get(i).lat > north) north = zone_config.PATH.get(i).lat;
                if (zone_config.PATH.get(i).lat < south) south = zone_config.PATH.get(i).lat;
                if (zone_config.PATH.get(i).lng > east) east = zone_config.PATH.get(i).lng;
                if (zone_config.PATH.get(i).lng < west) west = zone_config.PATH.get(i).lng;
            }
        }
    }

    //*************************************************************************************
    // Class Vehicle
    //*************************************************************************************
    
    // Vehicle stores the up-to-date status of a vehicle with a given vehicle_id
    // in the context of the current zone, e.g. is it currently within bounds
    class Vehicle {
        // These are attributes that come from the position record
        public String vehicle_id;
        public String label;
        public String route_id;
        public String trip_id;
        public Position prev_position;
        public boolean prev_within; // true if was within bounds at previous timestamp
        public Position position;
        public Float bearing;
        public String stop_id;
        public Long current_stop_sequence;

        // additional attributes used within this Zone
        public boolean init; // only true if this position has been initialized but not updated
        public boolean within; // true if within bounds at current timestamp
        public Long start_ts; // timestamp of successful start (otherwise 0)
        public Long start_ts_delta; // reliability indicator: (position.ts - prev_position.ts) at time of start

        // Initialize a new Vehicle object from a JSON position record
        public Vehicle(JsonObject position_record)
        {
            vehicle_id = position_record.getString("vehicle_id");

            label = position_record.getString("label","");
            route_id = position_record.getString("route_id","");
            trip_id = position_record.getString("trip_id","");
            bearing = position_record.getFloat("bearing",0.0f);
            stop_id = position_record.getString("stop_id","");
            current_stop_sequence = position_record.getLong("current_stop_sequence",0L);

            position = new Position();
            position.ts = position_record.getLong("timestamp");
            position.lat = position_record.getDouble("latitude");
            position.lng = position_record.getDouble("longitude");

            init = true; // will be reset to false when this entry is updated
            within = false;
            start_ts = 0L;
            start_ts_delta = 0L;

        }

        // update this existing Vehicle when a subsequent position_record has arrived
        public void update(JsonObject position_record)
        {
            prev_position = position;
            prev_within = within;

            Vehicle v = new Vehicle(position_record);
            label = v.label;
            route_id = v.route_id;
            trip_id = v.trip_id;
            position = v.position;
            bearing = v.bearing;
            stop_id = v.stop_id;
            current_stop_sequence = v.current_stop_sequence;

            init = false;
        }

    } // end class Vehicle
    
    //*************************************************************************************
    // Class Intersect
    //*************************************************************************************
    
    // Intersect class holds the result of an intersect test
    // Actual intersect method is in ZoneBoundary
    class Intersect {
        public Position position; // position is lat, long and timestamp (secs) of intersection point
        public boolean success;

        public Intersect()
        {
            success = false;
        }
    } // end class Intersect

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
} // end class Zone
