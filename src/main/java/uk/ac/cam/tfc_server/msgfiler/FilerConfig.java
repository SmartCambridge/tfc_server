package uk.ac.cam.tfc_server.msgfiler;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// FilerConfig.java
// Version 0.01
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' next-generation Realtime Intelligent Traffic Analysis system
//
// FilerConfig provides the configuration parameters for FilerUtils
//
// These will define a particular filer i.e. which messages to store where.
// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************

import io.vertx.core.json.JsonObject;

public class FilerConfig {

    public String module_name;
    public String module_id;
    
    public String source_address;     // eventbus address to listen for messages
    public FilerFilter source_filter; // filter criteria defining which message to store
    public String store_path;         // directory path to store message
    public String store_name;         // filename to store message
    public String store_mode;         // append | write

    public FilerConfig(JsonObject config)
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
                source_filter = new FilerFilter(config.getJsonObject("source_filter"));
            }
        store_path = config.getString("store_path");
        store_name = config.getString("store_name");
        store_mode = config.getString("store_mode");

        System.out.println(module_name+"."+module_id+": FilerConfig loaded:");
        System.out.println(module_name+"."+module_id+": "+source_address+","+source_filter.toString()+","+
                           store_path+","+store_name+","+store_mode);
        
    }
} // end class FilterConfig


