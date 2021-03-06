package uk.ac.cam.tfc_server.util;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.FeedHeader;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition;
import com.google.transit.realtime.GtfsRealtime.VehicleDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.Position;

public class GTFS {

  public static JsonObject buf_to_json(Buffer buf, String filename, String filepath) throws Exception
  {
      FeedMessage feed = FeedMessage.parseFrom(buf.getBytes());
      return feed_to_json_object(feed, filename, filepath);
  }

  private static JsonObject feed_to_json_object(FeedMessage feed, String filename, String filepath)
  {
    JsonObject feed_json_object = new JsonObject(); // object to hold entire message

    feed_json_object.put("filename",filename);
    feed_json_object.put("filepath",filepath);
    
    JsonArray ja = new JsonArray(); // array to hold GTFS 'entities' i.e. position records

    Long received_timestamp = System.currentTimeMillis() / 1000L; // note when feed was received

    // add (sent) timestamp as feed.timestamp (i.e. we are not using a 'header' sub-object
    FeedHeader header = feed.getHeader();
    if (header.hasTimestamp())
        {
            feed_json_object.put("timestamp", header.getTimestamp());
        }
            
    for (FeedEntity entity : feed.getEntityList())
        {
            try
                {
            if (entity.hasVehicle())
                {
                    VehiclePosition vehicle_pos = entity.getVehicle();
                    //PositionRecord pos_record = new PositionRecord();
                    JsonObject jo = new JsonObject();

                    jo.put("received_timestamp",received_timestamp);
                    
                    if (vehicle_pos.hasVehicle())
                        {
                            VehicleDescriptor vehicle_desc = vehicle_pos.getVehicle();
                            if (vehicle_desc.hasId())
                                {
                                    jo.put("vehicle_id",vehicle_desc.getId());
                                }
                            if (vehicle_desc.hasLabel())
                                {
                                    jo.put("label",vehicle_desc.getLabel());
                                }
                        }
                    if (vehicle_pos.hasPosition())
                        {
                            Position vpos = vehicle_pos.getPosition();
                            jo.put("latitude", vpos.getLatitude());
                            jo.put("longitude", vpos.getLongitude());
                            if (vpos.hasBearing())
                                {
                                    jo.put("bearing",vpos.getBearing());
                                }
                            jo.put("timestamp", vehicle_pos.getTimestamp());
                        }
                    if (vehicle_pos.hasTrip())
                        {
                            TripDescriptor trip = vehicle_pos.getTrip();
                            if (trip.hasTripId())
                                {
                                    jo.put("trip_id",trip.getTripId());
                                }
                            if (trip.hasRouteId())
                                {
                                    jo.put("route_id",trip.getRouteId());
                                }
                        }
                    if (vehicle_pos.hasCurrentStopSequence())
                        {
                            jo.put("current_stop_sequence",vehicle_pos.getCurrentStopSequence());
                        }
                    if (vehicle_pos.hasStopId())
                        {
                            jo.put("stop_id",vehicle_pos.getStopId());
                        }
                    if (vehicle_pos.hasTimestamp())
                        {
                            jo.put("timestamp",vehicle_pos.getTimestamp());
                        }

                    ja.add(jo);

                }
                } // end try
            catch (Exception e)
                {
                    System.err.println("FeedPlayer exception parsing position record");
                }
        }

    // finally... add JsonArray of feed 'FeedEntities' to feed_json_object
    feed_json_object.put("entities", ja);
    
    return feed_json_object;
  } // end feed_to_json_array()

} // end GTFS
