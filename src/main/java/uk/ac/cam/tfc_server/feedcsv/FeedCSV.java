package uk.ac.cam.tfc_server.feedcsv;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// FeedCSV.java
// Version 0.03
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' next-generation Realtime Intelligent Traffic Analysis system
//
// Subscribes to address "feed_vehicle" and writes file:
//   $TFC_DATA_CSV/YYYY/MM/DD/<filename>
// where <filename> = <UTC TIMESTAMP>_YYYY-MM-DD-hh-mm-ss.csv
//
// Publishes periodic status UP messages to address "system_status"
//
// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.Handler;
import io.vertx.core.file.FileSystem;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

import java.io.*;
import java.time.*;
import java.time.format.*;

public class FeedCSV extends AbstractVerticle {

  private final String CSV_FILE_HEADER = "timestamp,vehicle_id,label,route_id,trip_id,latitude,longitude,bearing,current_stop_sequence,stop_id";
  private final String ENV_VAR_CSV_PATH = "TFC_DATA_CSV";
  private final String EB_SYSTEM_STATUS = "system_status";
  private final int SYSTEM_STATUS_PERIOD = 10000; // period between system_status 'UP' messages
  private final int SYSTEM_STATUS_AMBER_SECONDS = 15; // delay before flagging system as AMBER
  private final int SYSTEM_STATUS_RED_SECONDS = 25; // delay before flagging system as RED
  
    
  private EventBus eb = null;
  private String tfc_data_csv = null;
    
  @Override
  public void start(Future<Void> fut) throws Exception {

    System.out.println("FeedCSV started! ");

    eb = vertx.eventBus();

    tfc_data_csv = System.getenv(ENV_VAR_CSV_PATH);
    if (tfc_data_csv == null)
    {
      System.err.println(ENV_VAR_CSV_PATH + " environment var not set -- aborting FeedCSV startup");
      vertx.close();
      return;
    }

    eb.consumer("feed_vehicle", message -> {
      System.out.println("FeedCSV got message from feed_vehicle address");
      //debug
      JsonObject feed_message = new JsonObject(message.body().toString());
      JsonArray entities = feed_message.getJsonArray("entities");
      System.out.println("FeedCSV feed_vehicle message #records: "+String.valueOf(entities.size()));

      handle_feed(feed_message);
    });
    
    // send periodic "system_status" messages
    vertx.setPeriodic(SYSTEM_STATUS_PERIOD, id -> {
      System.out.println("FeedCSV Sending system_status UP");
      // publish { "module_name": "console", "status": "UP" } on address "system_status"
      eb.publish(EB_SYSTEM_STATUS,
                 "{ \"module_name\": \"feedcsv\"," +
                   "\"status\": \"UP\"," +
                 "\"status_amber_seconds\": "+String.valueOf( SYSTEM_STATUS_AMBER_SECONDS ) + "," +
                 "\"status_red_seconds\": "+String.valueOf( SYSTEM_STATUS_RED_SECONDS ) +
                 "}" );
    });

  } // end start()

  private void handle_feed(JsonObject feed_message)
    {
        System.out.println(feed_message.getString("filename"));
        JsonArray entities = feed_message.getJsonArray("entities");

        String filename = feed_message.getString("filename");
        String filepath = feed_message.getString("filepath");

        FileSystem fs = vertx.fileSystem();
        
        Buffer buf = Buffer.buffer();

        // add csv header to buf
        buf.appendString(CSV_FILE_HEADER+"\n");
        
        for (int i = 0; i < entities.size(); i++)
            {
              JsonObject json_record = entities.getJsonObject(i);
              buf.appendString(entity_to_csv(json_record));
            }

        // Write file to $TFC_DATA_CSV
        //
        // if full directory path exists, then write file
        // otherwise create full path first
        final String csv_path = tfc_data_csv+"/"+filepath;
        System.out.println("Writing "+csv_path+"/"+filename+".csv");
        fs.exists(csv_path, result -> {
            if (result.succeeded() && result.result())
                {
                    System.out.println("FeedCSV: path "+csv_path+" exists");
                    write_file(fs, buf, csv_path+"/"+filename+".csv");
                }
            else
                {
                    System.out.println("FeedCSV: Creating directory "+csv_path);
                    fs.mkdirs(csv_path, mkdirs_result -> {
                            if (mkdirs_result.succeeded())
                                {
                                    write_file(fs, buf, csv_path+"/"+filename+".csv");
                                }
                            else
                                {
                                    System.err.println("FeedCSV error creating path "+csv_path);
                                }
                        });
                }
        });
    }

    private String entity_to_csv(JsonObject entity)
    {
        // timestamp,vehicle_id,label,route_id,trip_id,latitude,longitude,bearing,current_stop_sequence,stop_id
        String csv = String.valueOf(entity.getLong("timestamp")) +
            "," + entity.getString("vehicle_id","") +
            "," + entity.getString("label","") +
            "," + entity.getString("route_id","") +
            "," + entity.getString("trip_id","") +
            "," + String.valueOf(entity.getFloat("latitude")) +
            "," + String.valueOf(entity.getFloat("longitude")) +
            "," + String.valueOf(entity.getFloat("bearing",0.0f)) +
            "," + String.valueOf(entity.getLong("current_stop_sequence",0L)) +
            "," + entity.getString("stop_id","")+"\n";

        return csv;
    }
    
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


} // end class FeedCSV
