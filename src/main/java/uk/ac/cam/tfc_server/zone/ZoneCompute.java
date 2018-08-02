package uk.ac.cam.tfc_server.zone;

// ZoneCompute.java
//
// Provides the compute/analysis elements of a Zone.
// Used by Zone and BatcherWorker
//
import uk.ac.cam.tfc_server.zone.ZoneConfig;
import uk.ac.cam.tfc_server.zone.Vehicle;
import uk.ac.cam.tfc_server.util.Position;
import uk.ac.cam.tfc_server.util.Constants;
import uk.ac.cam.tfc_server.util.Log;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
// time/date crapola
import java.util.Date;
import java.text.SimpleDateFormat; // for timestamp conversion to HH:MM:SS
import java.time.LocalTime; // for timestamp duration conversion to HH:mm:ss
import java.util.TimeZone;
import java.util.HashMap;

import uk.ac.cam.tfc_server.util.IMsgHandler; // Interface for message handling in caller

public class ZoneCompute {

    private ZoneConfig zone_config;

    public IMsgHandler msg_handler; // will be called when Zone events occur

    private HashMap<String, Vehicle> vehicles; // dictionary to store vehicle status updated from feed
    
    private Box box;

    private Log logger;

    private final Long TS_DELTA_LIMIT = 350L; // if time delta (s) between consecutive position records is greater
                                              // than TS_DELTA_LIMIT, then do NOT use record for Zone entry/exit
    
    // zone_msg_buffer has a MsgBuffer entry for each zone.address
    //private HashMap<String, MsgBuffer> zone_msg_buffer; // stores zone completion messages since start of day

    public ZoneCompute(ZoneConfig zc, IMsgHandler mh)
    {
        zone_config = zc;

        msg_handler = mh;

        vehicles = new HashMap<String, Vehicle>();
        // create box object with boundaries of rectangle that includes this zone polygon
        box = new Box();
        //zone_msg_buffer = new HashMap<String, MsgBuffer>();

        logger = new Log(zc.LOG_LEVEL);

        logger.log(Constants.LOG_INFO, zc.MODULE_NAME+"."+zc.MODULE_ID+
                   ": started ZoneCompute(LOG_LEVEL "+zc.LOG_LEVEL+") for "+zc.ZONE_NAME);

    }

    public void handle_feed(JsonObject feed_message)
    {
        JsonArray position_records;

        // if data comes from GTFS FeedHandler then position records are in property "entities"
        if (feed_message.containsKey("entities"))
        {
            position_records = feed_message.getJsonArray("entities");
        }
        else
        // otherwise the position records will be in property "request_data"
        {
            position_records = feed_message.getJsonArray("request_data");
        }

        logger.log(Constants.LOG_DEBUG, zone_config.MODULE_NAME+"."+zone_config.MODULE_ID+
                   ": handle_feed for "+zone_config.ZONE_NAME+" with "+position_records.size()+" position records");

        for (int i = 0; i < position_records.size(); i++)
            {
              JsonObject position_record = position_records.getJsonObject(i);
              update_vehicle(position_record);
            }
    }

        // Update the vehicles[vehicle_id] record with this feed entry
    private void update_vehicle(JsonObject position_record)
    {

        // { "vehicle_id":"17147",
        //   "latitude":52.062675,
        //   "longitude":-1.331641,
        //   "bearing":12.0,
        //   "timestamp":1508322520,
        //   "acp_ts":1508322520,
        //   "acp_id":"17147",
        //   "acp_lat":52.062675,
        //   "acp_lng":-1.331641
        // }
        // update Vehicle object for this vehicle_id
        // shifting earlier location info to prev_position and prev_within
      String vehicle_id = Vehicle.vehicle_id(position_record);
      Vehicle v = vehicles.get(vehicle_id);
      if (v == null)
          {
              v = new Vehicle(vehicle_id, position_record);

              logger.log(Constants.LOG_DEBUG, zone_config.MODULE_NAME+"."+zone_config.MODULE_ID+
                   ": "+zone_config.ZONE_NAME+" new vehicle "+vehicle_id+" at "+v.position.toString());

              v.within = inside(v.position);
              vehicles.put(vehicle_id, v);
              return; // This is first position record for this vehicle, so just initialize entry
          }

      // These is existing position record for this vehicle, so update with the latest attributes from feed
      v.update(position_record);
      // And set the flag for whether this vehicle is within this Zone
      v.within = inside(v.position);

      // Error trap: If time between samples appears to have gone backwards, don't use for Zone entry/exit
      if (v.position.ts <= v.prev_position.ts)
          {
              return;
          }

      // Another error trap: if time delta between samples is too large, don't use for Zone entry/exit
      if (v.position.ts - v.prev_position.ts > TS_DELTA_LIMIT)
          {
              return;
          }
      
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
                      // Calculate how far the vehicle has already travelled in the zone
                      v.distance = i.position.distance(v.position);

                      // ZONE_START (entry via start line)
                      zone_start(v);
                      
                  }
              else
                  {
                      // ZONE_ENTRY (entry but not via start line)
                      zone_entry(v);
                  }
          }
      // IS VEHICLE TRAVELLING WITHIN ZONE?
      else if (v.within && v.prev_within)
          {
              // vehicle is continuing to travel within zone
              //System.out.println("Zone: vehicle_id("+vehicle_id+") inside zone "+ZONE_NAME);
              v.distance += v.prev_position.distance(v.position);
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
                      v.distance += v.prev_position.distance(i.position);
                      
                      // if we also have a good entry, then this is a successful COMPLETION
                      if (v.start_ts>0L)
                        {
                            // ZONE_COMPLETION
                            zone_completion(v, finish_ts);
                        }
                      else
                        {
                            // ZONE_EXIT via finish line but no prior good start
                            zone_finish_no_start(v, finish_ts);
                        }
                  }
              else
                  {
                      // ZONE EXIT but not via finish line
                      zone_exit(v);
                  }
              
              // Reset the Zone start time for this vehicle
              v.start_ts = 0L;
              v.start_ts_delta = 0L;
              v.distance = 0.0;
          }
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
    
    // ******************************************************************************************
    // ******************************************************************************************
    // ************* Handle each Zone event for current vehicle  ********************************
    // ************* i.e. ZONE_START, ZONE_COMPLETION, ZONE_EXIT ********************************
    // ******************************************************************************************
    // ******************************************************************************************

    private void zone_start(Vehicle v)
    {
        logger.log(Constants.LOG_DEBUG, "Zone: ,"+zone_config.MODULE_ID+",vehicle_id("+v.vehicle_id+
                          ") clean start at "+ts_to_time_str(v.start_ts) +
                          " start_ts_delta " + v.start_ts_delta);

      // ****************************************
      // Send ZONE_START msg
      // ****************************************

      JsonObject msg = new JsonObject();

      msg.put("module_name", zone_config.MODULE_NAME); 
      msg.put("module_id", zone_config.MODULE_ID);     // e.g. "madingley_road_in"
      msg.put("msg_type", Constants.ZONE_START);
      msg.put("vehicle_id", v.vehicle_id);
      msg.put("position_record", v.current_position_record);
      msg.put("ts", v.start_ts);
      msg.put("ts_delta", v.start_ts_delta);

      // Send zone_start message to common zone.address
      msg_handler.handle_msg(msg);
    }

    private void zone_entry(Vehicle v)
    {
      logger.log(Constants.LOG_DEBUG, "Zone: ,"+zone_config.MODULE_ID+",vehicle_id("+v.vehicle_id+
                         ") early entry at "+ts_to_time_str(v.position.ts)+
                         " ts_delta " + (v.position.ts - v.prev_position.ts));
      // ****************************************
      // Send ZONE_ENTRY msg
      // ****************************************

      JsonObject msg = new JsonObject();

      msg.put("module_name", zone_config.MODULE_NAME); // e.g. "zone"
      msg.put("module_id", zone_config.MODULE_ID);     // e.g. "madingley_road_in"
      msg.put("msg_type", Constants.ZONE_ENTRY);
      msg.put("vehicle_id", v.vehicle_id);
      msg.put("position_record", v.current_position_record);
      msg.put("ts", v.position.ts);
      msg.put("ts_delta", v.position.ts - v.prev_position.ts);

      // Send zone_entry message to common zone.address
      msg_handler.handle_msg(msg);
    }
    
    private void zone_completion(Vehicle v, Long finish_ts)
    {

      // exit completion message
      Long duration = finish_ts - v.start_ts; // time taken to transit this Zone

      // calculate duration of exit vector
      Long finish_ts_delta = v.position.ts - v.prev_position.ts;

      // Calculate average speed
      double speed = v.distance / duration;
      
      // Build console string and output
      // e.g. 2016-03-16 15:19:08,Cam Test,315,no_route,00:00:29,0.58,COMPLETED,15:11:41,15:18:55,00:07:14
      String completed_log = "Zone: ,"+zone_config.MODULE_ID+",";
      completed_log += "COMPLETED,";
      completed_log += v.vehicle_id;
      completed_log += v.current_position_record.toString()+",";
      completed_log += finish_ts+",";
      completed_log += duration+",";
      completed_log += v.distance+",";
      completed_log += speed+",";
      completed_log += ts_to_datetime_str(v.position.ts) + ",";
      completed_log += ts_to_time_str(v.start_ts) + ",";
      completed_log += ts_to_time_str(finish_ts) + ","; // finish time
      completed_log += duration_to_time_str(v.start_ts_delta) + ",";
      completed_log += duration_to_time_str(finish_ts_delta);

      logger.log(Constants.LOG_DEBUG, completed_log);

      // ****************************************
      // Send ZONE_COMPLETION msg
      // ****************************************

      JsonObject msg = new JsonObject();

      msg.put("module_name", zone_config.MODULE_NAME); // "zone"
      msg.put("module_id", zone_config.MODULE_ID);     // e.g. "madingley_road_in"
      msg.put("msg_type", Constants.ZONE_COMPLETION);
      msg.put("vehicle_id", v.vehicle_id);
      msg.put("position_record", v.current_position_record);
      msg.put("ts", finish_ts);
      msg.put("duration", duration);
      // note we send start_ts_delta + finish_ts_delta as the 'confidence' factor
      msg.put("ts_delta", finish_ts_delta + v.start_ts_delta);
      // report the distance travelled and average speed
      msg.put("distance", v.distance);
      msg.put("avg_speed", speed);

      // Send zone_completed message to common zone.address
      msg_handler.handle_msg(msg);
    }
    
    private void zone_finish_no_start(Vehicle v, Long finish_ts)
    {
      // output clean exit (no start) message
      logger.log(Constants.LOG_DEBUG, "Zone: ,"+zone_config.MODULE_ID+",vehicle_id("+v.vehicle_id+
                         ") clean exit (no start) at "+ts_to_time_str(finish_ts) +
                         " ts_delta " + (v.position.ts - v.prev_position.ts));
      // ****************************************
      // Send ZONE_EXIT msg
      // ****************************************

      JsonObject msg = new JsonObject();

      msg.put("module_name", zone_config.MODULE_NAME); // "zone"
      msg.put("module_id", zone_config.MODULE_ID);     // e.g. "madingley_road_in"
      msg.put("msg_type", Constants.ZONE_EXIT);
      msg.put("vehicle_id", v.vehicle_id);
      msg.put("position_record", v.current_position_record);
      msg.put("ts", finish_ts);
      msg.put("ts_delta", v.position.ts - v.prev_position.ts);

      // Send zone_completed message to common zone.address
      msg_handler.handle_msg(msg);
    }
    
    private void zone_exit(Vehicle v)
    {
      logger.log(Constants.LOG_DEBUG, "Zone: ,"+zone_config.MODULE_ID+",vehicle_id("+v.vehicle_id+
                         ") early exit at "+ts_to_time_str(v.position.ts)+
                         " ts_delta " + (v.position.ts - v.prev_position.ts));
      // ****************************************
      // Send ZONE_EXIT event message
      // ****************************************

      JsonObject msg = new JsonObject();

      msg.put("module_name", zone_config.MODULE_NAME); // "zone"
      msg.put("module_id", zone_config.MODULE_ID);     // e.g. "madingley_road_in"
      msg.put("msg_type", Constants.ZONE_EXIT);
      msg.put("vehicle_id", v.vehicle_id);
      msg.put("position_record", v.current_position_record);
      msg.put("ts", v.position.ts);
      msg.put("ts_delta", v.position.ts - v.prev_position.ts);

      // Send zone_completed message to common zone.address
      msg_handler.handle_msg(msg);
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

}
