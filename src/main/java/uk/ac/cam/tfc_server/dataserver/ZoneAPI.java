package uk.ac.cam.tfc_server.dataserver;

// ZoneAPI.java
//
// serves zone data via http / json (/api/zone/...)
// E.g.
//   /api/dataserver/zone/list
//   /api/dataserver/zone/config/madingley_road_in
//   /api/dataserver/zone/transits/madingley_road_in/2016/10/01
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
//import io.vertx.ext.web.Route;
import io.vertx.ext.web.RoutingContext;

import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;

public class ZoneAPI {

    // data directory name containing zone config files
    // i.e. files are in /media/tfc/<ZONE_CONFIG>/<files>
    static final String ZONE_CONFIG = "/sys/data_zone_config";

    // data directory name for zone transit data
    // i.e. files are in /media/tfc/<FEED_ID>/data_zone/YYYY/MM/DD/<files>
    static final String ZONE_TRANSITS = "/data_zone";

    private DataServer parent;

    public ZoneAPI(Vertx vertx, DataServer caller, Router router)
    {
	parent = caller;

	parent.logger.log(Constants.LOG_INFO, parent.MODULE_NAME+"."+parent.MODULE_ID+": ZoneAPI started");

        // ZONE TRANSITS API e.g. /api/dataserver/zone/transits/madingley_road_in/2016/10/01
        
        /*
        router.route(HttpMethod.GET, "/api/"+parent.MODULE_NAME+
                                     "/zone/transits/:zoneid/:yyyy/:MM/:dd").handler( ctx -> {
                String zone_id =  ctx.request().getParam("zoneid");
                String yyyy =  ctx.request().getParam("yyyy");
                String MM =  ctx.request().getParam("MM");
                String dd =  ctx.request().getParam("dd");
                parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                           ": API zone/transits/"+zone_id+"/"+yyyy+"/"+MM+"/"+dd);
                serve_transits(vertx, ctx, zone_id, yyyy, MM, dd);
            });
        */

        // Format /api/dataserver/zone/transits/madingley_road_in?date=2016-10-01
        router.route(HttpMethod.GET, "/api/"+parent.MODULE_NAME+
                                     "/zone/transits/:zoneid").handler( ctx -> {
                String zone_id =  ctx.request().getParam("zoneid");
                String date =  ctx.request().getParam("date");
                String feed_id = ctx.request().getParam("feed_id");
                if (feed_id == null)
                {
                    feed_id = parent.FEED_ID;
                }
                String yyyy = date.substring(0,4);
                String MM =  date.substring(5,7);
                String dd =  date.substring(8,10);
                parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                           ": API zone/transits/"+zone_id+"?date="+yyyy+"-"+MM+"-"+dd);
                serve_transits(vertx, ctx, feed_id, zone_id, yyyy, MM, dd);
            });
        
        // ZONE CONFIG API e.g. /api/dataserver/zone/config/madingley_road_in
        
        router.route(HttpMethod.GET, "/api/"+parent.MODULE_NAME+"/zone/config/:zoneid").handler( ctx -> {
                String zone_id =  ctx.request().getParam("zoneid");
                parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                           ": API zone/config/"+zone_id);
                serve_config(vertx, ctx, zone_id);
            });

        // ZONE LIST API e.g. /api/dataserver/zone/list
        
        router.route(HttpMethod.GET, "/api/"+parent.MODULE_NAME+"/zone/list").handler( ctx -> {
                parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                           ": API zone/config/list");
                serve_list(vertx, ctx);
            });
   }

    // Serve the zone/transits json data
    void serve_transits(Vertx vertx, RoutingContext ctx,
                                 String feed_id, String zone_id, String yyyy, String MM, String dd)
    {
        parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                   ": serving /api/"+parent.MODULE_NAME+"/zone/transits for "+zone_id+" "+yyyy+"/"+MM+"/"+dd);
            
        if (zone_id == null)
        {
            ctx.response().setStatusCode(404).end();
        }
        else
        {

            // build full filepath for data to be retrieved
            String filename = parent.DATA_PATH+"/"+feed_id+ZONE_TRANSITS+"/"+
                                yyyy+"/"+MM+"/"+dd+"/"+zone_id+"_"+yyyy+"-"+MM+"-"+dd+".txt";

            // read file which is actually a line-per-JsonObject, convert to JsonArray, and serve it
            serve_transit_file(vertx, ctx, filename);
        }
    }

    // Serve the zone/config json data
    void serve_config(Vertx vertx, RoutingContext ctx,
                      String zone_id)
    {
        parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                   ": serving /api/"+parent.MODULE_NAME+"/zone/config for "+zone_id);
            
        if (zone_id == null)
        {
            ctx.response().setStatusCode(404).end();
        }
        else
        {

            // build full filepath for data to be retrieved
            String filename = parent.DATA_PATH+ZONE_CONFIG+"/uk.ac.cam.tfc_server.zone."+zone_id+".json";

            serve_file(vertx, ctx, filename);
        }
    }

    // Serve the 'list of zones' json data
    //debug, until we have postgres, this is simply served from a file in the data_zone_config directory
    void serve_list(Vertx vertx, RoutingContext ctx)
    {
        parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                   ": serving /api/"+parent.MODULE_NAME+"/zone/list");
            
            // build full filepath for data to be retrieved
            String filename = parent.DATA_PATH+ZONE_CONFIG+"/list.json";

            serve_file(vertx, ctx, filename);
    }

    void serve_transit_file(Vertx vertx, RoutingContext ctx, String filename)
    {
        parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                   ": serving transit file "+filename);
            
        // read the file containing the data
        vertx.fileSystem().readFile(filename, fileres -> {

                if (fileres.succeeded()) {

                    // successful file read, so populate page data and return page

                    // Convert file contents to valid JSON
                    // File starts as JSON objects separated by newlines

                    String transit_data = fileres.result().toString();

                    // replace newlines with commas
                    transit_data = transit_data.replace("\n",",");

                    //remove trailing comma
                    transit_data = transit_data.substring(0,transit_data.length()-1);

                    // wrap with [] and we have a JSON array containing JSON objects...
                    transit_data = "["+transit_data+"]";


                    JsonArray transit_json = new JsonArray(transit_data);

                    HttpServerResponse response = ctx.response();
                    response.putHeader("content-type", "text/plain");

                    // build api JSON message including latest status values
                    JsonObject jo = new JsonObject();
                    jo.put("module_name", parent.MODULE_NAME);
                    jo.put("module_id", parent.MODULE_ID);
                    jo.put("request_data", transit_json);
                    response.end(jo.toString());
                } else {
                    ctx.response().setStatusCode(404).end();
                }
        });
    }

    // serve file, assumed to contain a valid JsonObject
    void serve_file(Vertx vertx, RoutingContext ctx, String filename)
    {
        parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                   ": serving file "+filename);
            
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
