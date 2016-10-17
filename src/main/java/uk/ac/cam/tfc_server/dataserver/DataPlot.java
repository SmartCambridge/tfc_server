package uk.ac.cam.tfc_server.dataserver;

// DataPlot.java
//
// Part of DataServer package, serves an XY time/value plot page
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

public class DataPlot {

    private DataServer parent;
    
    public DataPlot(Vertx vertx, DataServer caller, Router router)
    {
	parent = caller;

	parent.logger.log(Constants.LOG_INFO, parent.MODULE_NAME+"."+parent.MODULE_ID+": DataPlot started");
	
        // first check to see if we have a /plot/zone/zone_id with NO DATE, so do TODAY
        router.route(HttpMethod.GET, "/"+parent.BASE_URI+"/plot/zone/:zoneid").handler( ctx -> {
                String zone_id =  ctx.request().getParam("zoneid");
                LocalDateTime local_time = LocalDateTime.now();

                String dd = local_time.format(DateTimeFormatter.ofPattern("dd"));
                String MM = local_time.format(DateTimeFormatter.ofPattern("MM"));
                String yyyy = local_time.format(DateTimeFormatter.ofPattern("yyyy"));
                parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                           ": zone "+zone_id+" TODAY "+yyyy+"/"+MM+"/"+dd);
                serve_plot_zone(vertx, ctx, zone_id, yyyy, MM, dd);
            });

        // check for /plot/zone/zone_id/yyyy/MM/dd and show data for that previous day
        router.route(HttpMethod.GET, "/"+parent.BASE_URI+"/plot/zone/:zoneid/:yyyy/:MM/:dd").handler( ctx -> {
                String zone_id =  ctx.request().getParam("zoneid");
                String yyyy =  ctx.request().getParam("yyyy");
                String MM =  ctx.request().getParam("MM");
                String dd =  ctx.request().getParam("dd");
                parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                           ": zone "+zone_id+" "+yyyy+"/"+MM+"/"+dd);
                serve_plot_zone(vertx, ctx, zone_id, yyyy, MM, dd);
            });
   }

    // Serve the templates/dataserver_plot_zone.hbs web page
    public void serve_plot_zone(Vertx vertx, RoutingContext ctx,
                                 String zone_id, String yyyy, String MM, String dd)
    {
        parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                   ": serving dataserver_plot_zone.hbs for "+zone_id+" "+yyyy+"/"+MM+"/"+dd);
            
        if (zone_id == null)
        {
            ctx.response().setStatusCode(400).end();
        }
        else
        {

            ctx.put("config_base_uri", parent.BASE_URI); // e.g. "dataserver"
            
            ctx.put("config_zone_id",zone_id); // pass zone_id from URL into template var

            ctx.put("config_yyyy", yyyy);
            ctx.put("config_MM", MM);
            ctx.put("config_dd", dd);
            
            // build full filepath for data to be retrieved
            String filename = parent.DATA_PATH+"zone/"+yyyy+"/"+MM+"/"+dd+"/"+zone_id+"_"+yyyy+"-"+MM+"-"+dd+".txt";

            // read the file containing the data
            vertx.fileSystem().readFile(filename, fileres -> {

                    if (fileres.succeeded()) {

                        // successful file read, so populate page data and return page
                        parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                                          ": file read ok for "+filename);

                        // Convert file contents to valid JSON
                        // File starts as JSON objects separated by newlines

                        String plot_data = fileres.result().toString();

                        // replace newlines with commas
                        plot_data = plot_data.replace("\n",",");

                        //remove trailing comma
                        plot_data = plot_data.substring(0,plot_data.length()-1);

                        // wrap with [] and we have a JSON array containing JSON objects...
                        plot_data = "["+plot_data+"]";

                        ctx.put("config_plot_data", plot_data);

                        parent.template_engine.render(ctx, "templates/dataserver_plot_zone.hbs", res -> {
                                if (res.succeeded())
                                {
                                    ctx.response().end(res.result());
                                }
                                else
                                {
                                    ctx.fail(res.cause());
                                }
                            });
                    } else {
                        // unsuccessful file read, so return page with '' data
                        parent.logger.log(Constants.LOG_DEBUG, parent.MODULE_NAME+"."+parent.MODULE_ID+
                                          ": file read failed for "+filename);
                        // render the template WITHOUT the data, so page can tell user of error
                        parent.template_engine.render(ctx, "templates/dataserver_plot_zone.hbs", res -> {
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
            });
        }
    }
        
}
