package uk.ac.cam.tfc_server.dataserver;

// ParkingAPI.java
//
// serves parking data via http / json (/api/dataserver/parking/...)
// E.g.
//   /api/dataserver/parking/occupancy/grand-arcade-car-park?date=2016-10-01[&feed_id=cam_park_local]
//   /api/dataserver/parking/config/grand-arcade-car-park
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

    // parking config path:
    // data filename concat string to directory containing parking config files
    // i.e. files are in /media/tfc/sys/data_parking_config >
    static final String PARKING_CONFIG = "/sys/data_parking_config";
    // parking list path:
    static final String PARKING_LIST = PARKING_CONFIG+"/list.json";
    // parking occupancy path:
    static final String PARKING_OCCUPANCY = "/data_park"; // will be <feed_id>/data_park/yyyy/MM/dd

    private DataServer parent;

    public ParkingAPI(Vertx vertx, DataServer caller, Router router)
    {
	parent = caller;

	parent.logger.log(Constants.LOG_INFO, parent.MODULE_NAME+"."+parent.MODULE_ID+": ParkingAPI started");

        // PARKING OCCUPANCY API e.g. 
        // /api/dataserver/parking/occupancy/grand-arcade-car-park?date=2016-10-01[&feed_id=cam_park_local]
        
        router.route(HttpMethod.GET, "/api/"+parent.MODULE_NAME+
                                     "/parking/occupancy/:parkingid").handler( ctx -> {
                String parking_id =  ctx.request().getParam("parkingid");
                String date =  ctx.request().getParam("date");
                String yyyy = date.substring(0,4);
                String MM =  date.substring(5,7);
                String dd =  date.substring(8,10);
                // Either get the feed_id from the query or use default in parking config
                String feed_id = ctx.request().getParam("feed_id");
                // if we didn't get a feed_id in the query, get it from the config for that car park
                if (feed_id==null)
                {
                    parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                                      ": no feed_id in request, looking up in config");
                    // build full filepath for config data to be retrieved
                    String filename = parent.DATA_PATH+PARKING_CONFIG+"/"+parking_id+".json";

                    // read the config file and get the default feed_id for this car park
                    vertx.fileSystem().readFile(filename, fileres -> {
                            if (fileres.succeeded()) {
                                String config_data = fileres.result().toString();
                                JsonObject config_json = new JsonObject(config_data);
                                String config_feed_id = config_json.getString("feed_id");
                                parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                                      ": feed_id from config = "+config_feed_id);
                                serve_occupancy(vertx, ctx, config_feed_id, parking_id, yyyy, MM, dd);
                            } else {
                                ctx.response().setStatusCode(404).end();
                            }
                        });
                }
                else
                {
                    serve_occupancy(vertx, ctx, feed_id, parking_id, yyyy, MM, dd);
                }
            });
        
        // PARKING CONFIG API e.g. /api/dataserver/parking/config/grand-arcade-car-park
        
        router.route(HttpMethod.GET, "/api/"+parent.MODULE_NAME+
                                     "/parking/config/:parkingid").handler( ctx -> {
                String parking_id =  ctx.request().getParam("parkingid");
                parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                           ": API parking/config/"+parking_id);
                serve_config(vertx, ctx, parking_id);
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
                   ": serving /api/"+parent.MODULE_NAME+"/parking/occupancy/"+parking_id+
                   " from feed "+feed_id+" "+yyyy+"/"+MM+"/"+dd);
            
        if (parking_id == null)
        {
            ctx.response().setStatusCode(404).end();
        }
        else
        {

            // build full filepath for data to be retrieved
            String filename = parent.DATA_PATH+"/"+feed_id+PARKING_OCCUPANCY+"/"+
                              yyyy+"/"+MM+"/"+dd+"/"+parking_id+"_"+yyyy+"-"+MM+"-"+dd+".txt";

            // read file which is actually a line-per-JsonObject, convert to JsonArray, and serve it
            serve_occupancy_file(vertx, ctx, filename);
        }
    }

    // Serve the zone/config json data
    void serve_config(Vertx vertx, RoutingContext ctx, String parking_id)
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
            String filename = parent.DATA_PATH+PARKING_LIST;

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
