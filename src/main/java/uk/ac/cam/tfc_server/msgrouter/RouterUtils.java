package uk.ac.cam.tfc_server.msgrouter;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// RouterUtils.java
// Version 0.01
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' next-generation Realtime Adaptive City Platform
//
// RouterUtils provides the data storage procedures for MsgRouter and BatcherWorker
//
// Is configured via a config JsonObject which is stored as a RouterConfig
//   "source_address": the eventbus address to listen to for messages
//      e.g. "tfc.zone"
//   "flatten": the name of a JsonArray sub-field that is to be iterated into multiple messages
//   "source_filter" : a json object that specifies which subset of messages to write to disk
//      e.g. { "field": "msg_type", "compare": "=", "value": "zone_completion" }
//   "store_path" : a parameterized string giving the full filepath for storing the message
//      e.g. "/home/ijl20/tfc_server_data/data_zone/{{ts|yyyy}}/{{ts|MM}}/{{ts|dd}}"
//   "store_name" : a parameterized string giving the filename for storing the message
//      e.g. "{{module_id}}.txt"
//   "store_mode" : "write" | "append", defining whether the given file should be written or appended
//
// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************

import java.time.*;
import java.time.format.*;
import java.io.*;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.file.FileSystem;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.FileSystemException;

import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;

public class RouterUtils {

    private RouterConfig router_config;

    private Vertx vertx;
    
    public RouterUtils (Vertx v, RouterConfig rc)
    {
        router_config = rc;
        vertx = v;
    }

    // *******************************************************************************
    // route_msg()
    // *******************************************************************************
    public void route_msg(JsonObject msg)
    {
        System.out.println("MsgRouter."+router_config.module_id+": routing " + msg);

        /*

        // skip this message if if doesn't match the source_filter
        if (router_config.source_filter != null && !(router_config.source_filter.match(msg)))
            {
                return;
            }

        */
    }


} // end class RouterUtils
