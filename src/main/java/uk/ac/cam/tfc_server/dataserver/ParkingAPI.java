package uk.ac.cam.tfc_server.dataserver;

// ParkingAPI.java
//
// serves parking data via http / json (/api/dataserver/parking/...)
// E.g.
//   /api/dataserver/parking/occupancy/cam_park_local/grand-arcade-car-park/2016/10/01
//   /api/dataserver/parking/config/cam_park_local/grand-arcade-car-park
//   /api/dataserver/parking/list
//

import java.util.ArrayList;

import java.io.*;
import java.time.*;
import java.time.format.*;
import java.util.*;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.file.FileSystem;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;

public class ParkingAPI {

    // data filename concat string to directory containing zone config files
    // i.e. files are in /media/FEED_ID/data_PARKING_CONFIG/<files>
    static final String PARKING_CONFIG = "/sys/data_parking_config";

    private DataServer parent;

    public ParkingAPI(Vertx vertx, DataServer caller, Router router)
    {
	parent = caller;

	parent.logger.log(Constants.LOG_INFO, parent.MODULE_NAME+"."+parent.MODULE_ID+": ParkingAPI started");

        // PARKING OCCUPANCY API e.g. /api/dataserver/parking/occupancy/cam_park_local/grand-arcade-car-park/2016/10/01
        
        router.route(HttpMethod.GET, "/api/"+parent.MODULE_NAME+
                                     "/parking/occupancy/:feedid/:parkingid/:yyyy/:MM/:dd").handler( ctx -> {
                String feed_id = ctx.request().getParam("feedid");
                String parking_id =  ctx.request().getParam("parkingid");
                String yyyy =  ctx.request().getParam("yyyy");
                String MM =  ctx.request().getParam("MM");
                String dd =  ctx.request().getParam("dd");
                parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                           ": API parking/occupancy/"+feed_id+"/"+parking_id+"/"+yyyy+"/"+MM+"/"+dd);
                serve_occupancy(vertx, ctx, feed_id, parking_id, yyyy, MM, dd);
            });
        
        // PARKING CONFIG API e.g. /api/dataserver/parking/config/cam_park_local/grand-arcade-car-park
        
        router.route(HttpMethod.GET, "/api/"+parent.MODULE_NAME+
                                     "/parking/config/:feedid/:parkingid").handler( ctx -> {
                String feed_id =  ctx.request().getParam("feedid");
                String parking_id =  ctx.request().getParam("parkingid");
                parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                           ": API zone/config/"+feed_id+"/"+parking_id);
                serve_config(vertx, ctx, feed_id, parking_id);
            });

        // PARKING LIST API e.g. /api/dataserver/parking/list
        
        router.route(HttpMethod.GET, "/api/"+parent.MODULE_NAME+"/parking/list").handler( ctx -> {
                parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                           ": API parking/config/list");
                serve_list(vertx, ctx);
            });
   }

    // Serve the parking/occupancy json data
    void serve_occupancy(Vertx vertx, RoutingContext ctx,
                                 String feed_id, String parking_id, String yyyy, String MM, String dd)
    {
        parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                   ": serving /api/"+parent.MODULE_NAME+"/parking/occupancy for "+
                   feed_id+"."+parking_id+" "+yyyy+"/"+MM+"/"+dd);
            
        if (parking_id == null)
        {
            ctx.response().setStatusCode(404).end();
        }
        else
        {

            // build full filepath for data to be retrieved
            String filename = parent.DATA_PATH+"/"+feed_id+"/data_park/"+
                              yyyy+"/"+MM+"/"+dd+"/"+parking_id+"_"+yyyy+"-"+MM+"-"+dd+".txt";

            // read file which is actually a line-per-JsonObject, convert to JsonArray, and serve it
            serve_occupancy_file(vertx, ctx, filename);
        }
    }

    // Serve the zone/config json data
    void serve_config(Vertx vertx, RoutingContext ctx,
                      String feed_id, String parking_id)
    {
        parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                   ": serving /api/"+parent.MODULE_NAME+"/parking/config for "+parking_id);
            
        if (parking_id == null)
        {
            ctx.response().setStatusCode(404).end();
        }
        else
        {

            // build full filepath for data to be retrieved
            String filename = parent.DATA_PATH+PARKING_CONFIG+"/"+parking_id+".json";

            serve_file(vertx, ctx, filename);
        }
    }

    // Serve the 'list of car parks' json data
    //debug, until we have postgres, this is simply served from a file in the data_parking_config directory
    void serve_list(Vertx vertx, RoutingContext ctx)
    {
        parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                   ": serving /api/"+parent.MODULE_NAME+"/parking/list");
            
            // build full filepath for data to be retrieved
            String filename = parent.DATA_PATH+PARKING_CONFIG+"/list.json";

            serve_file(vertx, ctx, filename);
    }

    void serve_occupancy_file(Vertx vertx, RoutingContext ctx, String filename)
    {
        // read the file containing the data
        vertx.fileSystem().readFile(filename, fileres -> {

                if (fileres.succeeded()) {

                    // successful file read, so populate page data and return page

                    // Convert file contents to valid JSON
                    // File starts as JSON objects separated by newlines

                    String occupancy_data = fileres.result().toString();

                    // replace newlines with commas
                    occupancy_data = occupancy_data.replace("\n",",");

                    //remove trailing comma
                    occupancy_data = occupancy_data.substring(0,occupancy_data.length()-1);

                    // wrap with [] and we have a JSON array containing JSON objects...
                    occupancy_data = "["+occupancy_data+"]";

                    JsonArray response_json = new JsonArray(occupancy_data);

                    HttpServerResponse response = ctx.response();
                    response.putHeader("content-type", "text/plain");

                    // build api JSON message including latest status values
                    JsonObject jo = new JsonObject();
                    jo.put("module_name", parent.MODULE_NAME);
                    jo.put("module_id", parent.MODULE_ID);
                    jo.put("request_data", response_json);
                    response.end(jo.toString());
                } else {
                    ctx.response().setStatusCode(404).end();
                }
        });
    }

    // serve file, assumed to contain a valid JsonObject
    void serve_file(Vertx vertx, RoutingContext ctx, String filename)
    {
        // read the file containing the data
        vertx.fileSystem().readFile(filename, fileres -> {

                if (fileres.succeeded()) {

                    // successful file read, so populate page data and return page

                    JsonObject file_json = new JsonObject(fileres.result().toString());

                    HttpServerResponse response = ctx.response();
                    response.putHeader("content-type", "text/plain");

                    // build api JSON message including latest status values
                    JsonObject jo = new JsonObject();
                    jo.put("module_name", parent.MODULE_NAME);
                    jo.put("module_id", parent.MODULE_ID);
                    jo.put("request_data", file_json);
                    response.end(jo.toString());
                } else {
                    ctx.response().setStatusCode(404).end();
                }
        });
    }
        
}
