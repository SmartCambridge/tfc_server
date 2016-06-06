package uk.ac.cam.tfc_server.feedcsv;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// FeedCSV.java
// Version 0.10
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' next-generation Realtime Intelligent Traffic Analysis system
//
// Subscribes to address given in condig() as "feedcsv.feedhandler.address"
// and writes file to path:
//   TFC_DATA_CSV/YYYY/MM/DD/FILENAME
// where TFC_DATA_CSV is given in config() as "feedcsv.tfc_data_csv"
// and   FILENAME = <UTC TIMESTAMP>_YYYY-MM-DD-hh-mm-ss.csv
//
// Publishes periodic status UP messages to address given in config as "eb.system_status"
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

// other tfc_server classes
import uk.ac.cam.tfc_server.util.Log;

public class FeedCSV extends AbstractVerticle {
    // from config()
    private String MODULE_NAME;       // config module.name - normally "feedhandler"
    private String MODULE_ID;         // config module.id
    private String EB_SYSTEM_STATUS;  // config eb.system_status
    private String EB_MANAGER;        // config eb.manager
    
    private String FEEDHANDLER_ADDRESS; // config MODULE_NAME.feedhandler.address
    
    private String TFC_DATA_CSV = null; // config MODULE_NAME.tfc_data_csv

    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 15;
    private final int SYSTEM_STATUS_RED_SECONDS = 25;
    
    private EventBus eb = null;
    
    private final String CSV_FILE_HEADER = "timestamp,vehicle_id,label,route_id,trip_id,latitude,longitude,bearing,current_stop_sequence,stop_id";
     
  @Override
  public void start(Future<Void> fut) throws Exception {
      
    // load FeedCSV initialization values from config()
    if (!get_config())
          {
              Log.log_err("FeedCSV: "+ MODULE_ID + " failed to load initial config()");
              vertx.close();
              return;
          }
      
    System.out.println("FeedCSV: " + MODULE_ID + " started, listening to "+FEEDHANDLER_ADDRESS);

    eb = vertx.eventBus();

    eb.consumer(FEEDHANDLER_ADDRESS, message -> {
      System.out.println("FeedCSV got message from " + FEEDHANDLER_ADDRESS);
      //debug
      JsonObject feed_message = new JsonObject(message.body().toString());
      JsonArray entities = feed_message.getJsonArray("entities");
      System.out.println("FeedCSV feed_vehicle message #records: "+String.valueOf(entities.size()));

      handle_feed(feed_message);
    });
    
    // send periodic "system_status" messages
    vertx.setPeriodic(SYSTEM_STATUS_PERIOD, id -> {
      eb.publish(EB_SYSTEM_STATUS,
                 "{ \"module_name\": \""+MODULE_NAME+"\"," +
                   "\"module_id\": \""+MODULE_ID+"\"," +
                   "\"status\": \"UP\"," +
                   "\"status_amber_seconds\": "+String.valueOf( SYSTEM_STATUS_AMBER_SECONDS ) + "," +
                   "\"status_red_seconds\": "+String.valueOf( SYSTEM_STATUS_RED_SECONDS ) +
                 "}" );
    });

  } // end start()

    // Load initialization global constants defining this Zone from config()
    private boolean get_config()
    {
        // config() values needed by all TFC modules are:
        //   module.name - usually "feedcsv"
        //   module.id - unique module reference to be used by this verticle
        //   eb.system_status - String eventbus address for system status messages
        //   eb.manager - eventbus address for manager messages
        
        MODULE_NAME = config().getString("module.name");
        if (MODULE_NAME == null)
        {
          Log.log_err("FeedCSV: module.name config() not set");
          return false;
        }
        
        MODULE_ID = config().getString("module.id");
        if (MODULE_ID == null)
        {
          Log.log_err("FeedCSV."+MODULE_ID+": module.id config() not set");
          return false;
        }

        EB_SYSTEM_STATUS = config().getString("eb.system_status");
        if (EB_SYSTEM_STATUS == null)
        {
          Log.log_err("FeedCSV."+MODULE_ID+": eb.system_status config() not set");
          return false;
        }

        EB_MANAGER = config().getString("eb.manager");
        if (EB_MANAGER == null)
        {
          Log.log_err("FeedCSV."+MODULE_ID+": eb.manager config() not set");
          return false;
        }

        FEEDHANDLER_ADDRESS = config().getString(MODULE_NAME+".feedhandler.address");
        if (FEEDHANDLER_ADDRESS == null)
            {
                Log.log_err("FeedCSV."+MODULE_ID+": "+MODULE_NAME+".feedhandler.address config() not set");
                return false;
            }

        TFC_DATA_CSV = config().getString(MODULE_NAME+".tfc_data_csv");
        if (TFC_DATA_CSV == null)
        {
          Log.log_err("FeedCSV."+MODULE_ID+": "+MODULE_NAME+".tfc_data_csv config() not set");
          return false;
        }

        return true;
    }

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

        // Write file to TFC_DATA_CSV/YYYY/MM/DD/FILENAME
        // where TFC_DATA_CSV is given in config() as "feedcsv.tfc_data_csv"
        // and   FILENAME = <UTC TIMESTAMP>_YYYY-MM-DD-hh-mm-ss.csv
        //
        // if full directory path exists, then write file
        // otherwise create full path first
        final String csv_path = TFC_DATA_CSV+"/"+filepath;
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
                                    System.err.println("FeedCSV."+MODULE_ID+": error creating path "+csv_path);
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
        System.out.println("FeedCSV: File "+file_path+" written");
      } else {
        System.err.println("FeedCSV: write_file error ..." + result.cause());
      }
    });
  } // end write_file


} // end class FeedCSV
