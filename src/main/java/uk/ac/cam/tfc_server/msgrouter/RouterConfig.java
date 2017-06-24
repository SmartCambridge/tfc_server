package uk.ac.cam.tfc_server.msgrouter;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// RouterConfig.java
// Version 0.01
// Author: Ian Lewis ijl20@cam.ac.uk
//
// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************

import io.vertx.core.json.JsonObject;

public class RouterConfig {

    public String module_name;
    public String module_id;
    
    public String source_address;     // eventbus address to listen for messages
    public RouterFilter source_filter; // filter criteria defining which message to route onwards

    public RouterConfig(JsonObject config)
    {
        module_name = config.getString("module_name");
        module_id = config.getString("module_id");
        
        source_address = config.getString("source_address");
        // the 'source_filter' config() is optional
        JsonObject filter = config.getJsonObject("source_filter");
        if (filter == null)
            {
                source_filter = null;
            }
        else
            {
                source_filter = new RouterFilter(config.getJsonObject("source_filter"));
            }

        System.out.println(module_name+"."+module_id+": RouterConfig loaded.");
        
    }
} // end class RouterConfig


