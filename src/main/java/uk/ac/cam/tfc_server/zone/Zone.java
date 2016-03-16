package uk.ac.cam.tfc_server.zone;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// Zone.java
// Version 0.01
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' next-generation Realtime Intelligent Traffic Analysis system
//
// Subscribes to address "feed_vehicle" and sends "tfc.zone.update" messages
//
// Also writes zone updates to $TFC_DATA_ZONE/YYYY/MM/DD/<zone>.csv
//
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
//import java.time.*;
//import java.time.format.*;
import java.util.HashMap;
import java.util.Date;
import java.text.SimpleDateFormat;

// Position simply stores a lat/long/timestamp tuple
// and provides some utility methods, such as distance from another Position.
class Position {
    public double lat;
    public double lng;
    public Long ts;

    public Position()
    {
    }
    
    public Position(double init_lat, double init_lng)
    {
        this(init_lat, init_lng, 0L);
    }
    
    public Position(double init_lat, double init_lng, Long init_ts)
    {
        lat = init_lat;
        lng = init_lng;
        ts = init_ts;
    }

    public String toString() {
        return "("+String.format("%.4f",lat)+","+String.format("%.4f",lng)+"," + String.valueOf(ts)+")";
    }

    // Return distance in m between positions p1 and p2.
    // lat/longs in e.g. p1.lat etc
    double distance(Position p) {
        //double R = 6378137.0; // Earth's mean radius in meter
        double R = 6380000.0; // Earth's radius at Lat 52 deg in meter
        double dLat = Math.toRadians(p.lat - lat);
        double dLong = Math.toRadians(p.lng - lng);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(p.lat)) *
                Math.sin(dLong / 2) * Math.sin(dLong / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        double d = R * c;
        return d; // returns the distance in meter
    };
    
}

// Vehicle stores the up-to-date status of a vehicle with a given vehicle_id
// in the context of the current zone, e.g. is it currently within bounds
class Vehicle {
    public String vehicle_id;
    public String label;
    public String route_id;
    public String trip_id;
    public Position prev_position;
    public boolean prev_within; // true if was within bounds at previous timestamp
    public Position position;
    public boolean within; // true if within bounds at current timestamp
    public Float bearing;
    public String stop_id;
    public Long current_stop_sequence;
    public boolean init; // only true if this position has been initialized but not updated


    //debug
    //public Vehicle()
    //{
    //}
    
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

}

// Intersect class holds the result of an intersect test
// Actual intersect method is in ZoneBoundary
class Intersect {
    public Position position;
    public boolean success;

    public Intersect()
    {
        success = false;
    }
}

// Geographic definition of a zone, i.e. the path of the bounding polygon and start/finish line
// The start line is always between path[0]..path[1].
// The finish line is path[finish_index]..path[finish_index+1]
class ZoneBoundary {
    public Position center = null;
    public int zoom;
    public Position[] path;
    public int finish_index;
    public String name = null;
    
    private Box box;
    
    // The ZoneBoundary has a simplified boundary of a Box, i.e. a
    // simple rectangle. This permits a fast check of
    // whether a Position is outside the Zone. I.e. if
    // a Position is outside the Box, it's outside the Zone.
    class Box {
        double north = -90;
        double south = 90;
        double east = -180;
        double west = 180;

        Box() {
            for (int i=0; i<path.length; i++)
            {
                if (path[i].lat > north) north = path[i].lat;
                if (path[i].lat < south) south = path[i].lat;
                if (path[i].lng > east) east = path[i].lng;
                if (path[i].lng < west) west = path[i].lng;
            }
        }
    }

    public ZoneBoundary(Position[] path)
    {
        this.path = path;
        box = new Box();
    }
    
    // return true if Position p is INSIDE the Zone
    // http://stackoverflow.com/questions/13950062/checking-if-a-longitude-latitude-coordinate-resides-inside-a-complex-polygon-in
    public boolean inside(Position p) {
        // easy optimization - return false if position is outside bounding rectangle (box)
        if (p.lat > box.north || p.lat < box.south || p.lng < box.west || p.lng > box.east)
        return false;

        Position lastPoint = path[path.length - 1];
        boolean isInside = false;
        double x = p.lng;
        for (int i=0; i<path.length; i++)
        {
            Position point = path[i];
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

    // http://stackoverflow.com/questions/563198/how-do-you-detect-where-two-line-segments-intersect
    // Detect whether lines A->B and C->D intersect
    // return { intersect: true/false, position: LatLng (if lines do intersect), progress: 0..1 }
    // where 'progress' is how far the intersection is along the A->B path

    public Intersect intersect(int path_index, Vehicle v)
    {
        Intersect i = new Intersect();

        Position A = v.prev_position;
        Position B = v.position;

        Position C = path[path_index];
        Position D = path[path_index+1];

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
    
}

// ********************************************************************************************
// ********************************************************************************************
// ********************************************************************************************
// Here is the main Zone class definition
// ********************************************************************************************
// ********************************************************************************************
// ********************************************************************************************
public class Zone extends AbstractVerticle {

  // Config vars
  private final String ENV_VAR_ZONE_PATH = "TFC_DATA_ZONE"; // Linux var containing filepath root for csv files
    
  private final String EB_ZONE_UPDATE = "tfc.zone.update"; // eventbus address for zone updates
  private final String EB_CAM_BUS_FEED = "feed_vehicle"; // eventbus address for JSON feed position updates
    private final String EB_SYSTEM_STATUS = "system_status"; // eventbus status reporting address
    
  private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
  private final int SYSTEM_STATUS_AMBER_SECONDS = 15; // delay before flagging system as AMBER
  private final int SYSTEM_STATUS_RED_SECONDS = 25; // delay before flagging system as RED

  // Zone globals
  private EventBus eb = null;
  private String tfc_data_zone = null;

  private HashMap<String, Vehicle> vehicles; // dictionary to store vehicle status updated from feed

  private ZoneBoundary bounds = null; // object to store definition of zone boundary

  // **************************************************************************************
  // Zone Verticle Startup procedure
  @Override
  public void start(Future<Void> fut) throws Exception {

    System.out.println("Zone started! ");

    eb = vertx.eventBus();

    tfc_data_zone = System.getenv(ENV_VAR_ZONE_PATH);
    if (tfc_data_zone == null)
    {
      System.err.println(ENV_VAR_ZONE_PATH + " environment var not set -- aborting Zone startup");
      vertx.close();
      return;
    }

    // **********  Define the data structure for updated vehicle data  ***********************

    vehicles = new HashMap<String, Vehicle>();

    // **********  Define the Zone boundary  *************************************************

    // initialize bounds for this Zone
    //debug here we hardcode actual bounds parameters (will come from ZoneManager when written)
    bounds = new ZoneBoundary( new Position[] { new Position(52.201475385236485,0.12256622314453125),
                                   new Position(52.20352691291383,0.1272439956665039),
                                   new Position(52.195530680537125,0.1363849639892578),
                                   new Position(52.190716465371736,0.13153553009033203)
        });
    bounds.name = "Cam Test";
    bounds.center = new Position(52.200542498481255,0.1292002677917159);
    bounds.zoom = 15;
    bounds.finish_index = 2;

    //debug test intersect
    //Vehicle v = new Vehicle();
    //v.position = new Position(52.2,0.125,1458065671L);
    //v.prev_position = new Position(52.2045,0.125,1458065641L);
    //Intersect i = bounds.intersect(0,v);
    
    // **********  Set up connection to EventBus  ********************************************

    // set up a handler for the actual vehicle position feed messages
    eb.consumer(EB_CAM_BUS_FEED, message -> {

      JsonObject feed_message = new JsonObject(message.body().toString());

      handle_feed(feed_message);
    });

    // send periodic "system_status" messages
    vertx.setPeriodic(SYSTEM_STATUS_PERIOD, id -> {
      // publish { "module_name": "zone", "status": "UP" } on address "system_status"
      eb.publish(EB_SYSTEM_STATUS,
                 "{ \"module_name\": \"zone\"," +
                   "\"status\": \"UP\"," +
                   "\"status_amber_seconds\": "+String.valueOf( SYSTEM_STATUS_AMBER_SECONDS ) + "," +
                   "\"status_red_seconds\": "+String.valueOf( SYSTEM_STATUS_RED_SECONDS ) +
                 "}" );
      });

  } // end start()

// ***************************************************************************************
// **********                            *************************************************
// **********  Here is where we handle   *************************************************
// **********  each feed update          *************************************************
// **********                            *************************************************
// ***************************************************************************************
  
  private void handle_feed(JsonObject feed_message)
    {
        JsonArray entities = feed_message.getJsonArray("entities");

        String filename = feed_message.getString("filename");
        String filepath = feed_message.getString("filepath");

        System.out.println("Zone feed_vehicle ("+ String.valueOf(entities.size()) + " records): " + filename);

        for (int i = 0; i < entities.size(); i++)
            {
              JsonObject position_record = entities.getJsonObject(i);
              update_vehicle(position_record);
            }

        System.out.println("Zone "+ String.valueOf(vehicles.size()) + " records in vehicles HashMap)");
        //debug
        return;
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
                                    System.err.println("FeedCSV error creating path "+csv_path);
                                }
                        });
                }
        });
        */
    }

    private void update_vehicle(JsonObject position_record)
    {
      String vehicle_id = position_record.getString("vehicle_id");
      Vehicle v = vehicles.get(vehicle_id);
      if (v == null)
          {
              v = new Vehicle(position_record);
              v.within = bounds.inside(v.position);
              vehicles.put(vehicle_id, v);
              return; // This is first position record for this vehicle, so just initialize entry
          }

      // These is existing position record for this vehicle, so update
      v.update(position_record);
      v.within = bounds.inside(v.position);
      
      if (v.within && !v.prev_within)
          {
              //System.out.println("Zone: vehicle_id("+vehicle_id+") entered zone "+bounds.name);
              // Did vehicle cross start line?
              Intersect i = bounds.intersect(0,v);
              if (i.success)
                  {
                      Date intersect_time = new Date(i.position.ts * 1000);
                      SimpleDateFormat format_time = new SimpleDateFormat("HH:mm:ss");
                      String intersect_time_str = format_time.format(intersect_time);
                      System.out.println("Zone: "+bounds.name+ " "+
                                         "vehicle_id("+vehicle_id+") clean start at "+intersect_time_str);
                  }
              else
                  {
                      System.out.println("Zone: vehicle_id("+vehicle_id+") early entry into zone "+bounds.name);
                  }
          }
      if (v.within && v.prev_within)
          {
              //System.out.println("Zone: vehicle_id("+vehicle_id+") inside zone "+bounds.name);
          }
      if (!v.within && v.prev_within)
          {
              System.out.println("Zone: vehicle_id("+vehicle_id+") exitted zone "+bounds.name);
          }
    }
    
    private String entity_to_csv(JsonObject entity)
    {
        // timestamp,vehicle_id,label,route_id,trip_id,latitude,longitude,bearing,current_stop_sequence,stop_id
        String csv = String.valueOf(entity.getLong("timestamp")) +
            "," + entity.getString("vehicle_id","") +
            "," + entity.getString("label","") +
            "," + entity.getString("route_id","") +
            "," + entity.getString("trip_id","") +
            "," + String.valueOf(entity.getFloat("latitude")) +
            "," + String.valueOf(entity.getFloat("longitude")) +
            "," + String.valueOf(entity.getFloat("bearing",0.0f)) +
            "," + String.valueOf(entity.getLong("current_stop_sequence",0L)) +
            "," + entity.getString("stop_id","")+"\n";

        return csv;
    }
    
  private void write_file(FileSystem fs, Buffer buf, String file_path)
  {
    fs.writeFile(file_path, 
                 buf, 
                 result -> {
      if (result.succeeded()) {
        System.out.println("File "+file_path+" written");
      } else {
        System.err.println("FeedHandler write_file error ..." + result.cause());
      }
    });
  } // end write_file


} // end class FeedCSV
