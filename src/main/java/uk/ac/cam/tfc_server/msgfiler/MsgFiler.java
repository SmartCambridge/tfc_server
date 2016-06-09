package uk.ac.cam.tfc_server.msgfiler;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// MsgFiler.java
// Version 0.02
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' next-generation Realtime Intelligent Traffic Analysis system
//
// MsgFiler is dedicated to reading messages from the eventbus and
// storing them in the filesystem.
//
// Is given a list of 'filer' parameters in config() in "msgfiler.filers".  Each entry defines:
//   "source_address": the eventbus address to listen to for messages
//      e.g. "tfc.zone"
//   "source_filter" : a json object that specifies which subset of messages to write to disk
//      e.g. { "field": "msg_type", "compare": "=", "value": "zone_completion" }
//   "store_path" : a parameterized string giving the full filepath for storing the message
//      e.g. "/home/ijl20/tfc_server_data/data_zone/{{ts|yyyy}}/{{ts|MM}}/{{ts|dd}}/{{module_id}}.txt
//   "store_mode" : "write" | "append", defining whether the given file should be written or appended
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
import java.util.ArrayList;

// other tfc_server classes
import uk.ac.cam.tfc_server.util.Log;

public class MsgFiler extends AbstractVerticle {
    // from config()
    private String MODULE_NAME;       // config module.name - normally "msgfiler"
    private String MODULE_ID;         // config module.id - unique for this verticle
    private String EB_SYSTEM_STATUS;  // config eb.system_status
    private String EB_MANAGER;        // config eb.manager
    
    private ArrayList<FilerConfig> START_FILERS; // config msgfilers.filers parameters
    
    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 15;
    private final int SYSTEM_STATUS_RED_SECONDS = 25;

    private EventBus eb = null;
    
  @Override
  public void start(Future<Void> fut) throws Exception {
      
    // load initialization values from config()
    if (!get_config())
          {
              Log.log_err("MsgFiler."+ MODULE_ID + ": failed to load initial config()");
              vertx.close();
              return;
          }
      
    System.out.println("MsgFiler." + MODULE_ID + ": started");

    eb = vertx.eventBus();

    // iterate through all the filers to be started
    for (int i=0; i<START_FILERS.size(); i++)
        {
            start_filer(START_FILERS.get(i));
        }

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

    // start a Filer by registering a consumer to the given address
    private void start_filer(FilerConfig filer_config)
    {
        String filer_filter;
        if (filer_config.source_filter == null)
            {
                filer_filter = "";
            }
        else
            {
                filer_filter = " with " + filer_config.source_filter.toString();
            }
        System.out.println("MsgFiler."+MODULE_ID+": starting filer "+filer_config.source_address+ filer_filter);
    
        eb.consumer(filer_config.source_address, message -> {
            System.out.println("MsgFiler."+MODULE_ID+": got message from " + filer_config.source_address);
            JsonObject msg = new JsonObject(message.body().toString());
            
            System.out.println(msg.toString());

            if (filer_config.source_filter == null || filer_config.source_filter.match(msg))
                {
                    store_msg(msg, filer_config.store_path, filer_config.store_name, filer_config.store_mode);
                };
        });
    
    } // end start_filer
    
    private void store_msg(JsonObject msg, String config_path, String config_name, String config_mode)
    {
        System.out.println("MsgFiler."+MODULE_ID+": store_msg " + config_mode + " " + config_path + " " + config_name );
        System.out.println(msg.toString());

        // map the message values into the {{..}} placeholders in path and name
        String filepath;
        String filename;
        /*        try
                  {*/
            filepath = build_string(config_path, msg);
            filename = build_string(config_name, msg);

            System.out.println("MsgFiler."+MODULE_ID+": "+config_mode+ " " +filepath+"/"+filename);
            /* } 
        catch (Exception e)
        {
            Log.log_err("MsgFiler."+MODULE_ID+": failed buildstring with "+config_path+","+config_name);
            Log.log_err(msg.toString());
            return;
            }*/

        
        /*
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
        */
    } // end store_msg()

    // ************************************************************************************
    // build_string(String pattern, JsonObject msg)
    // ************************************************************************************
    // take a pattern and a message, and return the pattern populated with message values
    // e.g. "foo/bah/{{module_id}}" might become "foo/bah/zone_manager"
    // Patterns:
    //     {{<field_name>}}, populated via msg.getString(field_name)
    //     {{<field_name>|int}}, populated via msg.getLong(field_name)
    //     {{<field_name>|yyyy}}, get msg.getLong(field_name), parse it as a Unix timestamp, return year as "yyyy"
    //     {{<field_name>|MM}}, get msg.getLong(field_name), parse it as a Unix timestamp, return month as "MM"
    //     {{<field_name>|dd}}, get msg.getLong(field_name), parse it as a Unix timestamp, return day of month as "dd"
    private String build_string(String pattern, JsonObject msg)
    {
        final String PATTERN_START = "{{";
        final String PATTERN_END = "}}";
        
        int index = 0;
        String result = ""; // will hold the accumulated fully matched string
        
        while (index < pattern.length())
            {
                // get the indices of the start/end of the next {{..}}
                int pos_start = pattern.indexOf(PATTERN_START, index);
                int pos_end = pattern.indexOf(PATTERN_END, pos_start);
                // if pattern not found, then return string so far plus remainder
                if (pos_start < 0 || pos_end < 0)
                    {
                        result = result + pattern.substring(index);
                        return result;
                    }
                // we have a match for "{{..}}"
                // so accumulate the result up to the start of the "{{"
                // and find the value we have to replace "{{..}}" with
                result = result + pattern.substring(index, pos_start);
                // subst_pattern is the bit between the {{..}} e.g. "ts|yyyy"
                String subst_pattern = pattern.substring(pos_start + PATTERN_START.length(), pos_end);

                // filled_pattern is the value that should replace the {{..}} pattern e.g. "2016"
                String filled_pattern = fill_pattern(subst_pattern, msg);

                // add filled pattern to result so far
                result = result + filled_pattern;
                
                // move index along to just after the pattern
                index = pos_end + PATTERN_END.length();
            }
        return result;
    }

    // given a pattern and a msg, return the appropriate String
    // e.g. "ts|yyyy" -> "2016"
    // or "module_id" -> "zone"
    private String fill_pattern(String pattern, JsonObject msg)
    {
        final String PATTERN_FUN = "|";

        String field_name;

        // see if the pattern includes a function seperator, like "ts|yyyy"
        int fun_pos = pattern.indexOf(PATTERN_FUN);
        if (fun_pos < 0)
            {
                // simple case, no function separator, so just return msg field value
                return msg.getString(pattern);
            }
        else
            {
                field_name = pattern.substring(0, fun_pos);
            }

        // ok, we have a function to apply, so test each case

        if (pattern.endsWith(PATTERN_FUN+"int"))
            {
                Long field_value =  msg.getLong(field_name, 0L);
                return field_value.toString();
            }
        
        if (pattern.endsWith(PATTERN_FUN+"yyyy"))
            {
                Long field_value =  msg.getLong(field_name, 0L);

                Instant instant = Instant.ofEpochSecond(field_value);
                
                LocalDateTime local_time = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());

                String year = local_time.format(DateTimeFormatter.ofPattern("yyyy"));
                
                return year;
            }
        
        if (pattern.endsWith(PATTERN_FUN+"MM"))
            {
                Long field_value =  msg.getLong(field_name, 0L);
                Instant instant = Instant.ofEpochSecond(field_value);
                
                LocalDateTime local_time = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());

                String month = local_time.format(DateTimeFormatter.ofPattern("MM"));
                
                return month;
            }
        
        if (pattern.endsWith(PATTERN_FUN+"dd"))
            {
                Long field_value =  msg.getLong(field_name, 0L);
                Instant instant = Instant.ofEpochSecond(field_value);
                
                LocalDateTime local_time = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());

                String day = local_time.format(DateTimeFormatter.ofPattern("dd"));
                
                return day;
            }

        return pattern;
    }
        
    /*
    private void write_file(FileSystem fs, Buffer buf, String file_path)
    {
        fs.writeFile(file_path, 
                     buf, 
                     result -> {
          if (result.succeeded()) {
            System.out.println("MsgFiler: File "+file_path+" written");
          } else {
            Log.log_err("MsgFiler."+MODULE_ID+": write_file error ..." + result.cause());
          }
        });
    } // end write_file
    */
    
    // **********************************************************************************************
    // **********************************************************************************************
    // FilerConfig is the Java class that holds the 'Filer' parameters loaded from the Vertx config()
    // These will define a particular filer i.e. which messages to store where.
    // **********************************************************************************************
    // **********************************************************************************************
    private class FilerConfig {
        public String source_address;     // eventbus address to listen for messages
        public SourceFilter source_filter; // filter criteria defining which message to store
        public String store_path;         // directory path to store message
        public String store_name;         // filename to store message
        public String store_mode;         // append | write

        FilerConfig(JsonObject config)
        {
            source_address = config.getString("source_address");
            // the 'source_filter' config() is optional
            JsonObject filter = config.getJsonObject("source_filter");
            if (filter == null)
                {
                    source_filter = null;
                }
            else
                {
                    source_filter = new SourceFilter(config.getJsonObject("source_filter"));
                }
            store_path = config.getString("store_path");
            store_name = config.getString("store_name");
            store_mode = config.getString("store_mode");
        }
    } // end class FilterConfig

    // **********************************************************************************************
    // **********************************************************************************************
    // SourceFilter is created with field/compare/value paramters suitable for filtering JsonObject messages
    // and provides a 'match' method which returns 'true' if a given message meets those filter requirements
    // **********************************************************************************************
    // **********************************************************************************************
    private class SourceFilter {
        public String field;
        public String compare;
        public String value;

        SourceFilter(JsonObject source_filter)
        {
            field = source_filter.getString("field");
            compare = source_filter.getString("compare");
            value = source_filter.getString("value");
        }

        // match returns 'true' if msg.field compares ok with value
        // e.g. if field="module_id", compare="contains", value="zone"
        // then match will return true if this msg.module_id contains the string "zone"
        public boolean match(JsonObject msg)
        {
            String msg_value = msg.getString(field);
            if (msg_value == null)
            {
                return false;
            }

            switch (compare)
            {
                case "=":
                    return msg_value.equals(value);

                case ">":
                    return msg_value.compareTo(value) > 0;

                case "<":
                    return msg_value.compareTo(value) < 0;

                case "contains":
                    return msg_value.contains(value);

                default:
            }

            return false;
        } // end match()

        // return a string representation of this filter, e.g. "msg_type = zone_completion"
        public String toString()
        {
            return field + " " + compare + " " + value;
        }
    } // end class SourceFilter

    
    //**************************************************************************
    //**************************************************************************
    // Load initialization global constants defining this MsgFiler from config()
    //**************************************************************************
    //**************************************************************************
    private boolean get_config()
    {
        // config() values needed by all TFC modules are:
        //   module.name - usually "msgfiler"
        //   module.id - unique module reference to be used by this verticle
        //   eb.system_status - String eventbus address for system status messages
        //   eb.manager - eventbus address for manager messages
        
        MODULE_NAME = config().getString("module.name");
        if (MODULE_NAME == null)
        {
          Log.log_err("MsgFiler: module.name config() not set");
          return false;
        }
        
        MODULE_ID = config().getString("module.id");
        if (MODULE_ID == null)
        {
          Log.log_err("MsgFiler: module.id config() not set");
          return false;
        }

        EB_SYSTEM_STATUS = config().getString("eb.system_status");
        if (EB_SYSTEM_STATUS == null)
        {
          Log.log_err("MsgFiler."+MODULE_ID+": eb.system_status config() not set");
          return false;
        }

        EB_MANAGER = config().getString("eb.manager");
        if (EB_MANAGER == null)
        {
          Log.log_err("MsgFiler."+MODULE_ID+": eb.manager config() not set");
          return false;
        }

        // iterate through the msgfiler.filers config values
        START_FILERS = new ArrayList<FilerConfig>();
        JsonArray config_filer_list = config().getJsonArray(MODULE_NAME+".filers");
        for (int i=0; i<config_filer_list.size(); i++)
            {
                FilerConfig filer_config = new FilerConfig(config_filer_list.getJsonObject(i));
                
                START_FILERS.add(filer_config);
            }

        return true;
    } // end get_config()

} // end class FeedCSV
