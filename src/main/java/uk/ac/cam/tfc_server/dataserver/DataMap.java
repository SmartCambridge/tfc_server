package uk.ac.cam.tfc_server.dataserver;

// DataMap.java
//
// Part of DataServer package, serves a map page
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
import io.vertx.ext.web.Router;
//import io.vertx.ext.web.Route;
import io.vertx.ext.web.RoutingContext;

// handlebars for static .hbs web template files
import io.vertx.ext.web.templ.HandlebarsTemplateEngine;

import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;

public class DataMap {

    private DataServer parent;
    
    public DataMap(Vertx vertx, DataServer caller, Router router)
    {
	parent = caller;

	parent.logger.log(Constants.LOG_INFO, parent.MODULE_NAME+"."+parent.MODULE_ID+": DataMap started");
	
        // first check to see if we have a /plot/zone/zone_id with NO DATE, so do TODAY
        router.route(HttpMethod.GET, "/"+parent.BASE_URI+"/map/zone/:zoneid").handler( ctx -> {
                String zone_id =  ctx.request().getParam("zoneid");
                parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                                  ": serving page map/zone "+zone_id);
                serve_map_zone(vertx, ctx, zone_id);
            });

   }

    // Serve the templates/dataserver_map_zone.hbs web page
    public void serve_map_zone(Vertx vertx, RoutingContext ctx, String zone_id)
    {
        if (zone_id == null)
        {
            ctx.response().setStatusCode(400).end();
        }
        else
        {

            ctx.put("config_base_uri", parent.BASE_URI); // e.g. "dataserver"
            
            ctx.put("config_zone_id",zone_id); // pass zone_id from URL into template var

            ctx.put("config_google_map_api_key", parent.GOOGLE_MAP_API_KEY);

            // render the template WITHOUT the data, so page can tell user of error
            parent.template_engine.render(ctx, "templates/dataserver_map_zone.hbs", res -> {
                    if (res.succeeded())
                    {
                        ctx.response().end(res.result());
                    }
                    else
                    {
                        ctx.fail(res.cause());
                    }
                });
        }
    }
        
}
