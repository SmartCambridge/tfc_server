package uk.ac.cam.tfc_server.feedhandler;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// FeedHandler.java
// Version 0.06
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' next-generation Realtime Intelligent Traffic Analysis system
//
// Provides an HTTP server that receives the vehicle location data
// as Google GTFS-realtime POST data.
//
// Data is currently received as a POST every 30 seconds for approx 1200 vehicles
//
// FeedHandler will WRITE the raw binary post data into:
//   $TFC_DATA_MONITOR/<filename>
//   $TFC_DATA_BIN/YYYY/MM/DD/<filename>
//   $TFC_DATA_CACHE/YYYY/MM-DD/<filename>
// where <filename> = <UTC TIMESTAMP>_YYYY-MM-DD-hh-mm-ss.bin
// and any prior '.bin' files in $TFC_DATA_MONITOR will be deleted
//
// FeedHandler will publish the feed data as a JSON string on eventbus "feed_vehicle"
//
// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.file.FileSystem;
import io.vertx.core.eventbus.EventBus;
//import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

import java.io.*;
import java.time.*;
import java.time.format.*;
import java.util.*;

import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.FeedHeader;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition;
import com.google.transit.realtime.GtfsRealtime.VehicleDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.Position;

//import uk.ac.cam.tfc_server.gtfs.PositionRecord;

public class FeedHandler extends AbstractVerticle {

  private HttpServer http_server = null;
  private final int HTTP_PORT = 8080;

  private String tfc_data_cache = null; // environment var -- store backup bin files
  private String tfc_data_bin = null; // environment var -- store bin gtfs files
  private String tfc_data_monitor = null; // environment var -- store latest bin file

  private EventBus eb = null;
    
  @Override
  public void start(Future<Void> fut) throws Exception {

    boolean ok = true; // simple boolean to flag an abort during startup

    System.out.println("FeedHandler started!");

    eb = vertx.eventBus();
    
    tfc_data_cache = System.getenv("TFC_DATA_CACHE");
    if (tfc_data_cache == null)
    {
      System.err.println("TFC_DATA_CACHE environment var not set -- aborting feedhandler startup");
      ok = false;
      vertx.close();
    }

    if (ok)
    {
      tfc_data_bin = System.getenv("TFC_DATA_BIN");
      if (tfc_data_bin == null)
      {
        System.err.println("TFC_DATA_BIN environment var not set -- aborting feedhandler startup");
        ok = false;
        vertx.close();
      }
    }

    if (ok)
    {
      tfc_data_monitor = System.getenv("TFC_DATA_MONITOR");
      if (tfc_data_monitor == null)
      {
        System.err.println("TFC_DATA_MONITOR environment var not set -- aborting feedhandler startup");
        ok = false;
        vertx.close();
      }
    }

    if (ok)
    {
      http_server = vertx.createHttpServer();

      http_server.requestHandler(new Handler<HttpServerRequest>() {
        @Override
        public void handle(HttpServerRequest request) {
            System.out.println("FeedHandler called handle!");
            if(request.method() == HttpMethod.POST) {

              request.bodyHandler(body_data -> {
                System.out.println("FeedHandler called bodyHandler!");
                System.out.println("Full body received, length(" + body_data.length()+")");

                try {
                  process_gtfs(body_data);
                }
                catch (Exception ex) {
                  System.err.println("process_gtfs Exception");
                  System.err.println(ex.getMessage());
                }
                request.response().end("");
                    // here you can access the 
                    // fullRequestBody Buffer instance.
              });
            } else {
              request.response().end("<h1>TFC Feed Handler V2</h1> " +
                "<p>Vert.x 3 application</p");
            }
        }
      });

      http_server.listen(HTTP_PORT, result -> {
              System.out.println("FeedHandler listening on port " + String.valueOf(HTTP_PORT));
        if (result.succeeded()) {
          fut.complete();
        } else {
          fut.fail(result.cause());
        }
      });
    } // end if (ok)
  } // end start()

    // process the POST gtfs binary data
    private void process_gtfs(Buffer buf) throws Exception {

    LocalDateTime local_time = LocalDateTime.now();
    String day = local_time.format(DateTimeFormatter.ofPattern("dd"));
    String month = local_time.format(DateTimeFormatter.ofPattern("MM"));
    String year = local_time.format(DateTimeFormatter.ofPattern("yyyy"));
    String utc_ts = String.valueOf(System.currentTimeMillis() / 1000);

    // filename without the '.bin' suffix
    String filename = utc_ts+"_"+local_time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"));
    // sub-dir structure to store the file
    String filepath = year+"/"+month+"/"+day;
    // First just save the binary file to $TFC_DATA_MONITOR

    // Vert.x non-blocking file write...
    FileSystem fs = vertx.fileSystem();

    // Write file to $TFC_DATA_BIN
    //
    // if full directory path exists, then write file
    // otherwise create full path first
    final String bin_path = tfc_data_bin+"/"+filepath;
    System.out.println("Writing "+bin_path+"/"+filename+".bin");
    fs.exists(bin_path, result -> {
            if (result.succeeded() && result.result())
                {
                    System.out.println("process_gtfs: path "+bin_path+" exists");
                    write_file(fs, buf, bin_path+"/"+filename+".bin");
                }
            else
                {
                    System.out.println("Creating directory "+bin_path);
                    fs.mkdirs(bin_path, mkdirs_result -> {
                            if (mkdirs_result.succeeded())
                                {
                                    write_file(fs, buf, bin_path+"/"+filename+".bin");
                                }
                            else
                                {
                                    System.err.println("FeedHandler error creating path "+bin_path);
                                }
                        });
                }
        });

    // Write file to $TFC_DATA_CACHE
    //
    final String cache_path = tfc_data_cache+"/"+filepath;
    System.out.println("Writing "+cache_path+"/"+filename+".bin");
    // if full directory path exists, then write file
    // otherwise create full path first
    fs.exists(cache_path, result -> {
            if (result.succeeded() && result.result())
                {
                    System.out.println("process_gtfs: path "+cache_path+" exists");
                    write_file(fs, buf, cache_path+"/"+filename+".bin");
                }
            else
                {
                    System.out.println("Creating directory "+cache_path);
                    fs.mkdirs(cache_path, mkdirs_result -> {
                            if (mkdirs_result.succeeded())
                                {
                                    write_file(fs, buf, cache_path+"/"+filename+".bin");
                                }
                            else
                                {
                                    System.err.println("FeedHandler error creating path "+cache_path);
                                }
                        });
                }
        });

    // Write file to $TFC_DATA_MONITOR
    //
    System.out.println("Writing "+tfc_data_monitor+"/"+filename+".bin");
    fs.readDir(tfc_data_monitor, ".*\\.bin", monitor_result -> {
                            if (monitor_result.succeeded())
                                {
                                    for (String f: monitor_result.result())
                                        {
                                            System.out.println("Deleting "+f);
                                            fs.delete(f, delete_result -> {
                                                    if (!delete_result.succeeded())
                                                        {
                                                          System.err.println("FeedHandler error tfc_data_monitor delete: "+f);
                                                        }
                                                });
                                        }
                                    write_file(fs, buf, tfc_data_monitor+"/"+filename+".bin");
                                }
                            else
                                {
                                    System.err.println("FeedHandler error reading tfc_data_monitor path: "+tfc_data_monitor);
                                    System.err.println(monitor_result.cause());
                                }
    });

    // Here is where we process the individual position records
    FeedMessage feed = FeedMessage.parseFrom(buf.getBytes());
    //EventBus eb = vertx.eventBus();
    //eb.publish("feed_vehicle", Json.encode(feed));
    
    eb.publish("feed_vehicle", feed_to_json_object(feed,filename,filepath));
    System.out.println("FeedHandler published (feed_vehicle, pos_records)");
    
  } // end process_gtfs()

  private void write_file(FileSystem fs, Buffer buf, String file_path)
  {
    fs.writeFile(file_path, 
                 buf, 
                 result -> {
      if (result.succeeded()) {
        System.out.println("File "+file_path+" written");
      } else {
        System.err.println("FeedHandler write_file error ..." + result.cause());
      }
    });
  } // end write_file

  private JsonObject feed_to_json_object(FeedMessage feed, String filename, String filepath)
  {
    JsonObject feed_json_object = new JsonObject(); // object to hold entire message

    feed_json_object.put("filename",filename);
    feed_json_object.put("filepath",filepath);
    
    JsonArray ja = new JsonArray(); // array to hold GTFS 'entities' i.e. position records

    Long received_timestamp = System.currentTimeMillis() / 1000L; // note when feed was received

    // add (sent) timestamp as feed.timestamp (i.e. we are not using a 'header' sub-object
    FeedHeader header = feed.getHeader();
    if (header.hasTimestamp())
        {
            feed_json_object.put("timestamp", header.getTimestamp());
        }
            
    for (FeedEntity entity : feed.getEntityList())
        {
            try
                {
            if (entity.hasVehicle())
                {
                    VehiclePosition vehicle_pos = entity.getVehicle();
                    //PositionRecord pos_record = new PositionRecord();
                    JsonObject jo = new JsonObject();

                    jo.put("received_timestamp",received_timestamp);
                    
                    if (vehicle_pos.hasVehicle())
                        {
                            VehicleDescriptor vehicle_desc = vehicle_pos.getVehicle();
                            if (vehicle_desc.hasId())
                                {
                                    jo.put("vehicle_id",vehicle_desc.getId());
                                }
                            if (vehicle_desc.hasLabel())
                                {
                                    jo.put("label",vehicle_desc.getLabel());
                                }
                        }
                    if (vehicle_pos.hasPosition())
                        {
                            Position vpos = vehicle_pos.getPosition();
                            jo.put("latitude", vpos.getLatitude());
                            jo.put("longitude", vpos.getLongitude());
                            if (vpos.hasBearing())
                                {
                                    jo.put("bearing",vpos.getBearing());
                                }
                            jo.put("timestamp", vehicle_pos.getTimestamp());
                        }
                    if (vehicle_pos.hasTrip())
                        {
                            TripDescriptor trip = vehicle_pos.getTrip();
                            if (trip.hasTripId())
                                {
                                    jo.put("trip_id",trip.getTripId());
                                }
                            if (trip.hasRouteId())
                                {
                                    jo.put("route_id",trip.getRouteId());
                                }
                        }
                    if (vehicle_pos.hasCurrentStopSequence())
                        {
                            jo.put("current_stop_sequence",vehicle_pos.getCurrentStopSequence());
                        }
                    if (vehicle_pos.hasStopId())
                        {
                            jo.put("stop_id",vehicle_pos.getStopId());
                        }
                    if (vehicle_pos.hasTimestamp())
                        {
                            jo.put("timestamp",vehicle_pos.getTimestamp());
                        }

                    ja.add(jo);

                }
                } // end try
            catch (Exception e)
                {
                    System.err.println("Feedhandler exception parsing position record");
                }
        }

    // finally... add JsonArray of feed 'FeedEntities' to feed_json_object
    feed_json_object.put("entities", ja);
    
    return feed_json_object;
  } // end feed_to_json_array()
    
} // end FeedHandler class
