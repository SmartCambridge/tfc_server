package uk.ac.cam.tfc_server.dataserver;

// AQAPI.java
//
// serves air quality data via http / json (/api/dataserver/aq/...)
// E.g.
//   /api/dataserver/aq/reading/S-1134/CO?date=2016-10
//   /api/dataserver/aq/config/S-1134
//   /api/dataserver/aq/list
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

public class AQAPI {

    // aq config path:
    // data filename concat string to directory containing aq config files
    // i.e. files are in /media/tfc/sys/data_cam_aq_config >
    static final String AQ_CONFIG = "/sys/data_cam_aq_config";
    // aq list path:
    static final String AQ_LIST = AQ_CONFIG+"/list.json";
    // aq data readings path:
    static final String AQ_READING = "/data_bin"; // will be <feed_id>/data_park/yyyy/MM/dd

    private DataServer parent;

    public AQAPI(Vertx vertx, DataServer caller, Router router)
    {
	parent = caller;

	parent.logger.log(Constants.LOG_INFO, parent.MODULE_NAME+"."+parent.MODULE_ID+": AQAPI started");

        // AQ READINGS API e.g. 
        // /api/dataserver/aq/reading/S-1134?date=2016-12&feed_id=cam_aq&sensortype=CO
        
        router.route(HttpMethod.GET, "/api/"+parent.MODULE_NAME+
                                     "/aq/reading/:stationid").handler( ctx -> {
                String station_id =  ctx.request().getParam("stationid");
                String sensor_type =  ctx.request().getParam("sensor_type");
                String date =  ctx.request().getParam("date");
                String yyyy = date.substring(0,4);
                String mm =  date.substring(5,7);
                // Either get the feed_id from the query or use default in aq config
                String feed_id = ctx.request().getParam("feed_id");
                // if we didn't get a feed_id in the query, get it from the config for that station
                if (feed_id==null)
                {
                    parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                                      ": no feed_id in request, looking up in config");
                    // build full filepath for config data to be retrieved
                    String filename = parent.DATA_PATH+AQ_CONFIG+"/"+station_id+".json";

                    // read the config file and get the default feed_id for this car park
                    vertx.fileSystem().readFile(filename, fileres -> {
                            if (fileres.succeeded()) {
                                String config_data = fileres.result().toString();
                                JsonObject config_json = new JsonObject(config_data);
                                String config_feed_id = config_json.getString("FeedID");
                                parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                                      ": FeedID from config = "+config_feed_id);
                                serve_reading(vertx, ctx, config_feed_id, station_id, sensor_type,yyyy, mm);
                            } else {
                                ctx.response().setStatusCode(404).end();
                            }
                        });
                }
                else
                {
                    serve_reading(vertx, ctx, feed_id, station_id, sensor_type, yyyy, mm);
                }
            });
        
        // AQ CONFIG API e.g. /api/dataserver/aq/config/1134
        
        router.route(HttpMethod.GET, "/api/"+parent.MODULE_NAME+
                                     "/aq/config/:stationid").handler( ctx -> {
                String station_id =  ctx.request().getParam("stationid");
                parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                           ": API aq/config/"+station_id);
                serve_config(vertx, ctx, station_id);
            });

        // AQ LIST API e.g. /api/dataserver/aq/list
        
        router.route(HttpMethod.GET, "/api/"+parent.MODULE_NAME+"/aq/list").handler( ctx -> {
                parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                           ": API aq/config/list");
                serve_list(vertx, ctx);
            });
   }

    // Serve the aq/reading json data
    void serve_reading(Vertx vertx, RoutingContext ctx,
                                 String feed_id, String station_id, String sensor_type, String yyyy, String mm)
    {
        parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                   ": serving /api/"+parent.MODULE_NAME+"/aq/reading/"+station_id+"/"+sensor_type+
                   " from feed "+feed_id+" "+yyyy+"/"+mm);
            
        if (station_id == null)
        {
            ctx.response().setStatusCode(404).end();
        }
        else
        {

            // build full filepath for data to be retrieved
            String filename = parent.DATA_PATH+"/"+feed_id+AQ_READING+"/"+
                              yyyy+"/"+mm+"/"+station_id+"/"+station_id+"_"+sensor_type+"_"+yyyy+"-"+mm+".json";

            // read file which is a month's worth of reading for station_id/sensor_type
            serve_reading_file(vertx, ctx, filename);
        }
    }

    // Serve the aq/config json data
    void serve_config(Vertx vertx, RoutingContext ctx, String station_id)
    {
        parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                   ": serving /api/"+parent.MODULE_NAME+"/aq/config for "+station_id);
            
        if (station_id == null)
        {
            ctx.response().setStatusCode(404).end();
        }
        else
        {

            // build full filepath for data to be retrieved
            String filename = parent.DATA_PATH+AQ_CONFIG+"/"+station_id+".json";

            serve_file(vertx, ctx, filename);
        }
    }

    // Serve the 'list of sensor stations' json data
    //debug, until we have postgres, this is simply served from a file in the data_cam_aq_config directory
    void serve_list(Vertx vertx, RoutingContext ctx)
    {
        parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                   ": serving /api/"+parent.MODULE_NAME+"/aq/list");
            
            // build full filepath for data to be retrieved
            String filename = parent.DATA_PATH+AQ_LIST;

            serve_file(vertx, ctx, filename);
    }

    void serve_reading_file(Vertx vertx, RoutingContext ctx, String filename)
    {
        // read the file containing the data
        vertx.fileSystem().readFile(filename, fileres -> {

                if (fileres.succeeded()) {

                    // successful file read, so populate page data and return page

                    // Convert file contents to valid JSON
                    // File starts as JSON objects separated by newlines

                    String reading_data = fileres.result().toString();

                    JsonObject response_json = new JsonObject(reading_data);

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
