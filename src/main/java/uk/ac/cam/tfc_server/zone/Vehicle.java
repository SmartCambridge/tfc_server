package uk.ac.cam.tfc_server.zone;

// Vehicle.java
//*************************************************************************************
// Class Vehicle
//*************************************************************************************

// Vehicle stores the up-to-date status of a vehicle with a given vehicle_id
// in the context of the current zone, e.g. is it currently within bounds

import io.vertx.core.json.JsonObject;
import uk.ac.cam.tfc_server.util.Position;

public class Vehicle {
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
    Vehicle(JsonObject position_record)
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
    
