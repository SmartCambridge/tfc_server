package uk.ac.cam.tfc_server.feedmaker;

//***********************************************************************************************
//***********************************************************************************************
//   ParseFeedGTFS.java
//
//   Read the POSTed Google protobuf GTFS data, store to file, and broadcast on eventbus as Json
//
//   Author: ijl20
//
//***********************************************************************************************
//***********************************************************************************************

//
// Note that a FeedParser (like this one) ALWAYS returns a Json Array, even for a single record
//
/*
{
    "feed_id": "vix",
    "filename": "1508063941.992_2017-10-15-11-39-01",
    "filepath": "2017/10/15",
    "module_id": "test",
    "module_name": "feedmaker",
    "msg_type": "feed_bus_position",
    "timestamp": 1449056712,
    "ts": 1508063941
    "entities": [
        {
            "acp_lat": 51.882347,
            "acp_lng": -0.41706276,
            "acp_ts": 1449056671,
            "acp_id": "25",
            "bearing": 282.0,
            "label": "CBL-709",
            "latitude": 51.882347,
            "longitude": -0.41706276,
            "timestamp": 1449056671,
            "vehicle_id": "25"
        },
        {
            "acp_lat": 51.9181,
            "acp_lng": -0.4516892,
            "acp_ts": 1449056701,
            "acp_id": "30",
            "bearing": 330.0,
            "current_stop_sequence": 21,
            "label": "CBL-569",
            "latitude": 51.9181,
            "longitude": -0.4516892,
            "route_id": "CBL-10",
            "stop_id": "4290",
            "timestamp": 1449056701,
            "trip_id": "1165604-20151102-20151231",
            "vehicle_id": "30"
        },
    ],
}


*/
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.buffer.Buffer;

// other tfc_server classes
import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;

// All the Google Protobuf GTFS required classes
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.FeedHeader;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition;
import com.google.transit.realtime.GtfsRealtime.VehicleDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.Position;

public class ParseFeedGTFS implements FeedParser {

    private String area_id;

    private JsonObject config;

    private Log logger;
    
    ParseFeedGTFS(JsonObject config, Log logger)
    {
       this.config = config;

       this.area_id = config.getString("area_id","");

       this.logger = logger;

       logger.log(Constants.LOG_DEBUG, "ParseFeedGTFS started");
    }

    // Here is where we try and parse the page into a JsonObject
    public JsonObject parse(Buffer buf) throws Exception
    {
        logger.log(Constants.LOG_DEBUG, "ParseFeedGTFS.parse() called");

        FeedMessage feed = FeedMessage.parseFrom(buf.getBytes());

        JsonObject feed_json_object = new JsonObject(); // object to hold entire message

        JsonArray ja = new JsonArray(); // array to hold GTFS 'entities' i.e. position records

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

                                if (vehicle_pos.hasVehicle())
                                    {
                                        VehicleDescriptor vehicle_desc = vehicle_pos.getVehicle();
                                        if (vehicle_desc.hasId())
                                            {
                                                jo.put("vehicle_id",vehicle_desc.getId());
                                                jo.put(Constants.PLATFORM_PREFIX+"id",vehicle_desc.getId());
                                            }
                                        if (vehicle_desc.hasLabel())
                                            {
                                                jo.put("label",vehicle_desc.getLabel());
                                            }
                                    }
                                if (vehicle_pos.hasPosition())
                                    {
                                        Position vpos = vehicle_pos.getPosition();
                                        // Latitude
                                        jo.put("latitude", vpos.getLatitude());
                                        jo.put(Constants.PLATFORM_PREFIX+"lat",vpos.getLatitude());
                                        // Longitude
                                        jo.put("longitude", vpos.getLongitude());
                                        jo.put(Constants.PLATFORM_PREFIX+"lng",vpos.getLongitude());

                                        if (vpos.hasBearing())
                                            {
                                                jo.put("bearing",vpos.getBearing());
                                            }
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
                                        // Timestamp
                                        jo.put("timestamp", vehicle_pos.getTimestamp());
                                        jo.put(Constants.PLATFORM_PREFIX+"ts", vehicle_pos.getTimestamp());
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

    }

} // end ParseFeedGTFS

