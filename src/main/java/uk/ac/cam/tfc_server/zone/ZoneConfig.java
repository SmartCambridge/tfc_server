package uk.ac.cam.tfc_server.zone;

// ZoneConfig.java
//
// Part of Zone package, holds configuration values defining the Zone
//
// This object is used by both Zone (built from vertx config())
// and also BatcherWorker (which will pass a ZoneConfig object to the Zone)

import java.util.ArrayList;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

import uk.ac.cam.tfc_server.util.Position;
import uk.ac.cam.tfc_server.util.Log;

public class ZoneConfig {

    public String MODULE_NAME;       // config module.name
    public String MODULE_ID;         // config module.id
    public String ZONE_NAME;         // config zone.name
    public ArrayList<Position> PATH; // config zone.path
    public Position CENTER;          // config zone.center
    public int ZOOM;                 // config zone.zoom
    public int FINISH_INDEX;         // config zone.finish_index
    public boolean valid;

    public ZoneConfig(JsonObject config)
    {
        valid = true;
        
        MODULE_NAME = config.getString("module.name"); // "zonemanager"
        if (MODULE_NAME==null)
            {
                Log.log_err("ZoneConfig: no module.name in config()");
                valid = false;
                return;
            }
        
        MODULE_ID = config.getString("module.id"); // A, B, ...
        if (MODULE_ID==null)
            {
                Log.log_err("ZoneConfig: no module.id in config()");
                valid = false;
                return;
            }

        ZONE_NAME = config.getString("zone.name");

        PATH = new ArrayList<Position>();
        JsonArray json_path = config.getJsonArray("zone.path", new JsonArray());
        for (int i=0; i < json_path.size(); i++) {
            PATH.add(new Position(json_path.getJsonObject(i)));
        }

        CENTER = new Position(config.getJsonObject("zone.center"));

        ZOOM = config.getInteger("zone.zoom");
        
        FINISH_INDEX = config.getInteger("zone.finish_index");
    }
    
}
