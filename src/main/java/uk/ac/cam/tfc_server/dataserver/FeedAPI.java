package uk.ac.cam.tfc_server.dataserver;

// FeedAPI.java
//
// serves feed data via http / json (/api/dataserver/feed/...)
// E.g.
//   /api/dataserver/feed/now/cam_park_rss
//   /api/dataserver/feed/prev/vix
//   /api/dataserver/feed/config/cam_park_local
//   /api/dataserver/feed/list
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

public class FeedAPI {

    // feed config path:
    static final String FEED_CONFIG = "/sys/data_feed_config";
    // feed list path:
    static final String FEED_LIST = FEED_CONFIG+"/list.json";
    // feed current data (i.e. now) path:
    static final String FEED_NOW_JSON = "/data_monitor_json/post_data.json";

    private DataServer parent;

    public FeedAPI(Vertx vertx, DataServer caller, Router router)
    {
	parent = caller;

	parent.logger.log(Constants.LOG_INFO, parent.MODULE_NAME+"."+parent.MODULE_ID+": FeedAPI started");
        
        // FEED CONFIG API e.g. /api/dataserver/feed/config/cam_park_rss
        
        router.route(HttpMethod.GET, "/api/"+parent.MODULE_NAME+
                                     "/feed/config/:feedid").handler( ctx -> {
                String feed_id =  ctx.request().getParam("feedid");
                parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                           ": API feed/config/"+feed_id);
                serve_config(vertx, ctx, feed_id);
            });

        // FEED LIST API e.g. /api/dataserver/feed/list
        
        router.route(HttpMethod.GET, "/api/"+parent.MODULE_NAME+"/feed/list").handler( ctx -> {
                parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                           ": API feed/list");
                serve_list(vertx, ctx);
            });

        // FEED NOW API e.g. /api/dataserver/feed/now/<feed_id>
        // Will return the CURRENT data for the feed, i.e. the data most recently received
        router.route(HttpMethod.GET, "/api/"+parent.MODULE_NAME+
                                     "/feed/now/:feedid").handler( ctx -> {
                String feed_id =  ctx.request().getParam("feedid");
                parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                           ": API feed/now/"+feed_id);
 
                String filename = parent.DATA_PATH+"/"+feed_id+FEED_NOW_JSON;

                serve_file(vertx, ctx, filename);
            });

        // FEED PREV API e.g. /api/dataserver/feed/prev/<feed_id>
        // Will return the PREVIOUS received data for the feed (vs NOW above)
        router.route(HttpMethod.GET, "/api/"+parent.MODULE_NAME+
                                     "/feed/previous/:feedid").handler( ctx -> {
                String feed_id =  ctx.request().getParam("feedid");
                parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                           ": API feed/previous/"+feed_id);
 
                String filename = parent.DATA_PATH+"/"+feed_id+FEED_NOW_JSON+Constants.PREV_FILE_SUFFIX;

                serve_file(vertx, ctx, filename);
            });
   }

    // Serve the feed/config json data
    void serve_config(Vertx vertx, RoutingContext ctx, String feed_id)
    {
        parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                   ": serving /api/"+parent.MODULE_NAME+"/feed/config for "+feed_id);
            
        if (feed_id == null)
        {
            ctx.response().setStatusCode(404).end();
        }
        else
        {

            // build full filepath for data to be retrieved
            String filename = parent.DATA_PATH+FEED_CONFIG+"/"+feed_id+".json";

            serve_file(vertx, ctx, filename);
        }
    }

    // Serve the 'list' json data
    //debug, until we have postgres, this is simply served from a file in the data config directory
    void serve_list(Vertx vertx, RoutingContext ctx)
    {
        parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                   ": serving /api/"+parent.MODULE_NAME+"/feed/list");
            
            // build full filepath for data to be retrieved
            String filename = parent.DATA_PATH+FEED_LIST;

            serve_file(vertx, ctx, filename);
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
