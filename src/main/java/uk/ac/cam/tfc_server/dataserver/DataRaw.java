package uk.ac.cam.tfc_server.dataserver;

// DataRaw.java
//
// Part of DataServer package, allows download of raw data files
//

import java.io.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.nio.file.Paths;
import java.nio.file.Path;

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

public class DataRaw {

    private DataServer ds;
    
    public DataRaw(Vertx vertx, DataServer caller, Router router)
    {
	ds = caller;

	ds.logger.log(Constants.LOG_INFO, ds.MODULE_NAME+"."+ds.MODULE_ID+": DataRaw started");
	
        // first check to see if we have a /raw/day/bin with NO DATE, so do TODAY
        router.route(HttpMethod.GET, "/"+ds.BASE_URI+"/raw/day/:rawname").handler( ctx -> {

                LocalDateTime local_time = LocalDateTime.now();

                String dd = local_time.format(DateTimeFormatter.ofPattern("dd"));
                String MM = local_time.format(DateTimeFormatter.ofPattern("MM"));
                String yyyy = local_time.format(DateTimeFormatter.ofPattern("yyyy"));
                String rawname =  ctx.request().getParam("rawname");
           
                ds.logger.log(Constants.LOG_DEBUG, ds.MODULE_NAME+"."+ds.MODULE_ID+
                           ": raw day TODAY "+rawname+"/"+yyyy+"/"+MM+"/"+dd);
                serve_raw_day(vertx, ctx, rawname, yyyy, MM, dd);
            });

        // check for /raw/day/bin/yyyy/MM/dd and show data for that previous day
        router.route(HttpMethod.GET, "/"+ds.BASE_URI+"/raw/day/:rawname/:yyyy/:MM/:dd").handler( ctx -> {

                String yyyy =  ctx.request().getParam("yyyy");
                String MM =  ctx.request().getParam("MM");
                String dd =  ctx.request().getParam("dd");
                String rawname =  ctx.request().getParam("rawname");

                ds.logger.log(Constants.LOG_DEBUG, ds.MODULE_NAME+"."+ds.MODULE_ID+
                           ": raw day "+rawname+"/"+yyyy+"/"+MM+"/"+dd);
                serve_raw_day(vertx, ctx, rawname, yyyy, MM, dd);
            });

        // check for /raw/file/<rawname>/yyyy/MM/dd/<filename> and return contents of that file
        router.route(HttpMethod.GET, "/"+ds.BASE_URI+"/raw/file/:rawname/:yyyy/:MM/:dd/:filename").handler( ctx -> {

                String yyyy =  ctx.request().getParam("yyyy");
                String MM =  ctx.request().getParam("MM");
                String dd =  ctx.request().getParam("dd");
                String rawname =  ctx.request().getParam("rawname");
                String filename = ctx.request().getParam("filename");

                ds.logger.log(Constants.LOG_DEBUG, ds.MODULE_NAME+"."+ds.MODULE_ID+
                           ": raw file "+rawname+"/"+yyyy+"/"+MM+"/"+dd+"/"+filename);
                serve_raw_file(vertx, ctx, rawname, yyyy, MM, dd, filename);
            });

        // check for /raw/file/<rawname>/<filename> and return contents of that file
        // e.g. /raw/file/monitor_json/post_data.json
        router.route(HttpMethod.GET, "/"+ds.BASE_URI+"/raw/file/:rawname/:filename").handler( ctx -> {

                String rawname =  ctx.request().getParam("rawname");
                String filename = ctx.request().getParam("filename");

                ds.logger.log(Constants.LOG_DEBUG, ds.MODULE_NAME+"."+ds.MODULE_ID+
                           ": raw file "+rawname+"/"+filename);
                serve_filepath(vertx, ctx, ds.DATA_PATH+"/"+ds.FEED_ID+"/data_"+rawname+"/"+ filename);
            });

   }

    // Serve the templates/dataserver_raw_day.hbs web page
    public void serve_raw_day(Vertx vertx, RoutingContext ctx,
                                 String rawname, String yyyy, String MM, String dd)
    {
        ds.logger.log(Constants.LOG_DEBUG, ds.MODULE_NAME+"."+ds.MODULE_ID+
                   ": serving dataserver_raw_day.hbs for "+rawname+"/"+yyyy+"/"+MM+"/"+dd);
            
        if (rawname == null)
        {
            ds.logger.log(Constants.LOG_INFO, ds.MODULE_NAME+"."+ds.MODULE_ID+
                          ": rawname is null, responding 400 error");
            ctx.response().setStatusCode(400).end();
        }
        else
        {

            ctx.put("config_base_uri", ds.BASE_URI); // e.g. "dataserver"
            
            ctx.put("config_rawname", rawname);
            ctx.put("config_yyyy", yyyy);
            ctx.put("config_MM", MM);
            ctx.put("config_dd", dd);
            
            // build full filepath for data to be retrieved
            String raw_path = ds.DATA_PATH+"/"+ds.FEED_ID+"/data_"+rawname+"/"+yyyy+"/"+MM+"/"+dd;

            // read list of filenames from directory
            vertx.fileSystem().readDir(raw_path, res -> {
                if (res.succeeded())
                    {
                        // get res.result() list of files and display in raw_day.hbs
  
                        ds.logger.log(Constants.LOG_DEBUG, ds.MODULE_NAME+"."+ds.MODULE_ID+
                                      ": raw day read successfully");
                                // sort the files from the directory into timestamp order
                        Collections.sort(res.result());

                        String files_json = "[";
                        
                        for (int i = 0; i < res.result().size(); i++)
                            {
                                if (i!=0)
                                    {
                                        files_json += ",";
                                    }
                                Path p = Paths.get(res.result().get(i));
                                String filename = p.getFileName().toString();
                                files_json += "\""+filename+"\"";
                            }

                        files_json += "]";

                        ctx.put("config_files", files_json);

                        ds.logger.log(Constants.LOG_INFO, ds.MODULE_NAME+"."+ds.MODULE_ID+
                                   ": DataRaw rendering "+raw_path);

                        // render template with this list of filenames
                        ds.template_engine.render(ctx, "templates/dataserver_raw_day.hbs", t_res -> {
                                if (t_res.succeeded())
                                    {
                                        ctx.response().end(t_res.result());
                                    }
                                else
                                    {
                                        Log.log_err("DataRaw render error");
                                        ctx.fail(t_res.cause());
                                    }
                            });
                    }
                else
                    {
                        ds.logger.log(Constants.LOG_DEBUG, ds.MODULE_NAME+"."+ds.MODULE_ID+
                                   ": DataRaw directory read error "+raw_path);
                        // render template with empty list of filenames
                        //debug on directory read error we should put message on web page
                        ds.template_engine.render(ctx, "templates/dataserver_raw_day.hbs", t_res -> {
                                if (t_res.succeeded())
                                    {
                                        ctx.response().end(t_res.result());
                                    }
                                else
                                    {
                                        Log.log_err("DataRaw render error");
                                        ctx.fail(t_res.cause());
                                    }
                            });
                    }
                });
        }
    }

    // Serve the templates/dataserver_raw_file.hbs web page
    public void serve_raw_file(Vertx vertx, RoutingContext ctx,
                                 String rawname, String yyyy, String MM, String dd, String filename)
    {
        ds.logger.log(Constants.LOG_DEBUG, ds.MODULE_NAME+"."+ds.MODULE_ID+
                   ": serving dataserver_raw_file.hbs for "+yyyy+"/"+MM+"/"+dd+"/"+filename);
            
        if (rawname == null)
        {
            ctx.response().setStatusCode(400).end();
        }
        else
        {

            // build full filepath for data to be retrieved
            String filepath = ds.DATA_PATH+"/"+ds.FEED_ID+"/data_"+rawname+"/"+yyyy+"/"+MM+"/"+dd+"/"+filename;

            serve_filepath(vertx, ctx, filepath);

        }
    }

    public void serve_filepath(Vertx vertx, RoutingContext ctx, String filepath)
    {
        // read the file containing the data
        vertx.fileSystem().readFile(filepath, fileres -> {

                if (fileres.succeeded()) {

                    ds.logger.log(Constants.LOG_DEBUG, ds.MODULE_NAME+"."+ds.MODULE_ID+": "+
                                  "DataRaw file "+filepath+" read successfully");

                    ctx.response().putHeader("content-type", "application/octet-stream");

                    // successful file read, so return page
                    ctx.response().end(fileres.result());

                } else {
                    ds.logger.log(Constants.LOG_DEBUG, ds.MODULE_NAME+"."+ds.MODULE_ID+": "+
                                  "DataRaw file "+filepath+" read failed");

                    // return no-found error
                    ctx.response().setStatusCode(404).end();
                }
        });
    }
        
}
