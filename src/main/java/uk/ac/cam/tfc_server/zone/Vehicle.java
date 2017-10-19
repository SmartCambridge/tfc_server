package uk.ac.cam.tfc_server.zone;

// Vehicle.java
//*************************************************************************************
// Class Vehicle
//*************************************************************************************

// Vehicle stores the up-to-date status of a vehicle with a given vehicle_id
// in the context of the current zone, e.g. is it currently within bounds

import io.vertx.core.json.JsonObject;

import uk.ac.cam.tfc_server.util.Constants;
import uk.ac.cam.tfc_server.util.Position;

public class Vehicle {

    // STATIC method to get vehicle_id from position_record, used by ZoneCompute
    // We pick up the vehicle_id EITHER from "acp_id" (preferred) or a "vehicle_id" property
    public static String vehicle_id(JsonObject position_record)
    {
        return position_record.getString(Constants.PLATFORM_PREFIX+"id",position_record.getString("vehicle_id"));
    }

    // These are attributes that come from the position record
    public String vehicle_id;

    // latest and previous position records
    public JsonObject current_position_record;
    public JsonObject prev_position_record;

    public Position position;
    public Position prev_position;
    public boolean prev_within; // true if was within bounds at previous timestamp

    // additional attributes used within this Zone
    public boolean init; // only true if this position has been initialized but not updated
    public boolean within; // true if within bounds at current timestamp
    public Long start_ts; // timestamp of successful start (otherwise 0)
    public Long start_ts_delta; // reliability indicator: (position.ts - prev_position.ts) at time of start

    // Initialize a new Vehicle object from a JSON position record
    Vehicle(String vehicle_id, JsonObject position_record)
    {
        // initialize the position record 2-record stack
        prev_position_record = new JsonObject();
        current_position_record = position_record.copy();

        this.vehicle_id = vehicle_id;

        position = get_position();

        init = true; // will be reset to false when this entry is updated
        within = false;
        start_ts = 0L;
        start_ts_delta = 0L;

    }

    // update this existing Vehicle when a subsequent position_record has arrived
    public void update(JsonObject position_record)
    {
        // push this position record onto our 2-record stack
        prev_position_record = current_position_record.copy();
        current_position_record = position_record.copy();

        prev_position = position;
        prev_within = within;

        position = get_position();

        init = false;
    }

    Position get_position()
    {
        // for ts, lat, lng we will use EITHER "acp_ts", "acp_lat", "acp_lng" (preferred) or the GTFS values
        Position position = new Position();
        position.ts = current_position_record.getLong(Constants.PLATFORM_PREFIX+"ts",current_position_record.getLong("timestamp"));
        position.lat = current_position_record.getDouble(Constants.PLATFORM_PREFIX+"lat", current_position_record.getDouble("latitude"));
        position.lng = current_position_record.getDouble(Constants.PLATFORM_PREFIX+"lng", current_position_record.getDouble("longitude"));
        return position;
    }    

} // end class Vehicle
    
