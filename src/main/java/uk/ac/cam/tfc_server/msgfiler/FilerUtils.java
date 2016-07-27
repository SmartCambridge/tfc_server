package uk.ac.cam.tfc_server.msgfiler;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// FilerUtils.java
// Version 0.01
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' next-generation Realtime Intelligent Traffic Analysis system
//
// FilerUtils provides the data storage procedures for MsgFiler and BatcherWorker
//
// Is configured via a config JsonObject which is stored as a FilerConfig
//   "source_address": the eventbus address to listen to for messages
//      e.g. "tfc.zone"
//   "source_filter" : a json object that specifies which subset of messages to write to disk
//      e.g. { "field": "msg_type", "compare": "=", "value": "zone_completion" }
//   "store_path" : a parameterized string giving the full filepath for storing the message
//      e.g. "/home/ijl20/tfc_server_data/data_zone/{{ts|yyyy}}/{{ts|MM}}/{{ts|dd}}"
//   "store_name" : a parameterized string giving the filename for storing the message
//      e.g. "{{module_id}}.txt"
//   "store_mode" : "write" | "append", defining whether the given file should be written or appended
//
// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************

import java.time.*;
import java.time.format.*;
import java.io.*;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.file.FileSystem;
import io.vertx.core.buffer.Buffer;

import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;

public class FilerUtils {

    private FilerConfig filer_config;

    private Vertx vertx;
    
    public FilerUtils (Vertx v, FilerConfig fc)
    {
        filer_config = fc;
        vertx = v;
    }

    // **********************************
    // store_msg()
    // Store the message to the filesystem
    // **********************************
    public void store_msg(JsonObject msg)
    {
        System.out.println("MsgFiler."+filer_config.module_id+": store_msg " +
                           filer_config.store_mode + " " + filer_config.store_path + " " + filer_config.store_name );
        System.out.println(msg);

        // map the message values into the {{..}} placeholders in path and name
        String filepath;
        String filename;
        filepath = build_string(filer_config.store_path, msg);
        filename = build_string(filer_config.store_name, msg);

        System.out.println("MsgFiler."+filer_config.module_id+": "+filer_config.store_mode+ " " +filepath+"/"+filename);

        String msg_str = msg.toString();
        
        FileSystem fs = vertx.fileSystem();
        
        // if full directory path exists, then write file
        // otherwise create full path first
        
        fs.exists(filepath, result -> {
            if (result.succeeded() && result.result())
                {
                    System.out.println("MsgFiler."+filer_config.module_id+": path "+filepath+" exists");
                    write_file(msg_str, filepath+"/"+filename, filer_config.store_mode);
                }
            else
                {
                    System.out.println("MsgFiler."+filer_config.module_id+": creating directory "+filepath);
                    fs.mkdirs(filepath, mkdirs_result -> {
                            if (mkdirs_result.succeeded())
                                {
                                    write_file(msg_str, filepath+"/"+filename, filer_config.store_mode);
                                }
                            else
                                {
                                    Log.log_err("MsgFiler."+filer_config.module_id+": error creating path "+filepath);
                                }
                        });
                }
        });
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

    // *****************************************************************
    // write_file()
    // either overwrite (ASYNC) or append(SYNC) according to config_mode
    private void write_file(String msg, String file_path, String config_mode)
    {
        if (config_mode.equals(Constants.FILE_WRITE))
            {
                overwrite_file(msg, file_path);
            }
        else // append - this is a SYNCHRONOUS operation...
            {
                vertx.executeBlocking(fut -> {
                        append_file(msg, file_path);
                        fut.complete();
                    }, res -> { }
                    );
            }
    }        
        
    // **********************************************************
    // overwrite_file()
    // will do an ASYNCHRONOUS operation, i.e. return immediately
    private void overwrite_file(String msg, String file_path)
    {
        FileSystem fs = vertx.fileSystem();
        Buffer buf = Buffer.buffer(msg);
        fs.writeFile(file_path, 
                     buf, 
                     result -> {
          if (result.succeeded()) {
            System.out.println("MsgFiler: File "+file_path+" written");
          } else {
            Log.log_err("MsgFiler."+filer_config.module_id+": write_file error ..." + result.cause());
          }
        });
    } // end write_file

    // *********************************************************************
    // append_file()
    // BLOCKING code that will open and append 'msg'+'\n' to file 'filepath'
    private void append_file(String msg, String file_path)
    {
        System.out.println("MsgFiler."+filer_config.module_id+": append_file "+ file_path);

        BufferedWriter bw = null;

        try {
            // note FileWriter second arg 'true' => APPEND MODE
            bw = new BufferedWriter(new FileWriter(file_path, true));
            bw.write(msg);
            bw.newLine();
            bw.flush();
        } catch (IOException ioe) {
            Log.log_err("MsgFiler."+filer_config.module_id+": append_file failed for "+file_path);
        } finally {                       // always close the file
            if (bw != null) try {
                    bw.close();
                } catch (IOException ioe2) {
                    // just ignore it
                }
        } // end try/catch/finally

    } // end append_file

} // end class FilerUtils
