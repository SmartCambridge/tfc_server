package uk.ac.cam.tfc_server.gtfs;

import io.vertx.core.json.JsonObject;

// timestamp,id,label,route_id,trip_id,latitude,longitude,bearing,current_stop_sequence,stop_id 
public class PositionRecord
{
    public Long   timestamp  = null;
    public String vehicle_id = null;
    public String label      = null;
    public String route_id   = null;
    public String trip_id    = null;
    public Float  latitude   = null;
    public Float  longitude  = null;
    public Float  bearing    = null;
    public Long   current_stop_sequence = null;
    public String stop_id    = null;

    public Long   received_timestamp = null; // set when record is first initialized

    // constructor
    public PositionRecord()
    {
        received_timestamp = System.currentTimeMillis() / 1000L;
    }

    public PositionRecord(String json)
    {
        JsonObject pos_record = new JsonObject(json);
        timestamp = pos_record.getLong("timestamp");
        vehicle_id = pos_record.getString("vehicle_id");
        label = pos_record.getString("label");
        route_id = pos_record.getString("route_id");
        trip_id = pos_record.getString("trip_id");
        latitude = pos_record.getFloat("latitude");
        longitude = pos_record.getFloat("longitude");
        bearing = pos_record.getFloat("bearing");
        current_stop_sequence = pos_record.getLong("current_stop_sequence");
        stop_id = pos_record.getString("stop_id");
    }
    
    private String json_pair(String name, Long value)
    {
        return "\""+name+"\": " + String.valueOf(value);
    }
    
    private String json_pair(String name, String value)
    {
        return "\""+name+"\": " + (value == null ? "null" : "\""+value+"\"");
    }
    
    private String json_pair(String name, Float value)
    {
        return "\""+name+"\": " + String.valueOf(value);
    }
    
    // return PositionRecord as JSON
    public String toJSON()
    {
        String json = "{ ";
        json += json_pair("timestamp", timestamp);
        json += ", "+json_pair("vehicle_id", vehicle_id);
        json += ", "+json_pair("label", label);
        json += ", "+json_pair("route_id", route_id);
        json += ", "+json_pair("latitude", latitude);
        json += ", "+json_pair("longitude", longitude);
        json += ", "+json_pair("bearing", bearing);
        json += ", "+json_pair("current_stop_sequence", current_stop_sequence);
        json += ", "+json_pair("stop_id", stop_id);

        json += "\n}";
        return json;
    }
}
