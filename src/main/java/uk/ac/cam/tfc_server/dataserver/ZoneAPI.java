package uk.ac.cam.tfc_server.dataserver;

// ZoneAPI.java
//
// serves zone data via http / json (/api/zone/...)
// E.g.
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

    private DataServer parent;

    public ZoneAPI(Vertx vertx, DataServer caller, Router router)
    {
	parent = caller;

	parent.logger.log(Constants.LOG_INFO, parent.MODULE_NAME+"."+parent.MODULE_ID+": ZoneAPI started");
	
        // check for /plot/zone/zone_id/yyyy/MM/dd and show data for that previous day
        //debug using 'dataserver' in api path for zone, possibly should use variable
        router.route(HttpMethod.GET, "/api/dataserver/zone/transits/:zoneid/:yyyy/:MM/:dd").handler( ctx -> {
                String zone_id =  ctx.request().getParam("zoneid");
                String yyyy =  ctx.request().getParam("yyyy");
                String MM =  ctx.request().getParam("MM");
                String dd =  ctx.request().getParam("dd");
                parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                           ": zone "+zone_id+" "+yyyy+"/"+MM+"/"+dd);
                serve_transits(vertx, ctx, zone_id, yyyy, MM, dd);
            });
   }

    // Serve the json data
    public void serve_transits(Vertx vertx, RoutingContext ctx,
                                 String zone_id, String yyyy, String MM, String dd)
    {
        parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                   ": serving /api/dataserver/zone/transits for "+zone_id+" "+yyyy+"/"+MM+"/"+dd);
            
        if (zone_id == null)
        {
            ctx.response().setStatusCode(404).end();
        }
        else
        {

            // build full filepath for data to be retrieved
            String filename = parent.DATA_PATH+"zone/"+yyyy+"/"+MM+"/"+dd+"/"+zone_id+"_"+yyyy+"-"+MM+"-"+dd+".txt";

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
                        jo.put("transits", transit_json);
                        response.end(jo.toString());
                    } else {
                        ctx.response().setStatusCode(404).end();
                    }
            });
        }
    }
        
}
