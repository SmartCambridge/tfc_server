package uk.ac.cam.tfc_server.feedplayer;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// FeedPlayer.java
// Version 0.03
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' next-generation Realtime Intelligent Traffic Analysis system
//
// Reads GTFS-format binary files from the filesystem, broadcasts messages to eventbus
//
// FeedHandler will publish the feed data as a JSON string on eventbus "feedplayer.address"
// For the spec of the eventbus messages see README.md in the feedplayer directory
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
//import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

import java.io.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.text.SimpleDateFormat;
    
import uk.ac.cam.tfc_server.util.GTFS;
import uk.ac.cam.tfc_server.util.Constants;

// ********************************************************************************************
// ********************************************************************************************
// ********************************************************************************************
// Here is the main FeedPlayer class definition
// ********************************************************************************************
// ********************************************************************************************
// ********************************************************************************************

public class FeedPlayer extends AbstractVerticle {
    // Config vars
    private String MODULE_NAME; // from config()
    private String MODULE_ID; // from config()
    private String EB_SYSTEM_STATUS; // eventbus status reporting address

    private String FEEDPLAYER_ADDRESS; // eventbus address for JSON feed position updates

    private String TFC_DATA_BIN; // root of bin files
    private Long   START_TS;   // UTC timestamp for first position record file to publish
    private Long   FINISH_TS;  // UTC timestamp to end feed
    private int    RATE; // milliseconds between each published feed message
    
    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 15; // delay before flagging system as AMBER
    private final int SYSTEM_STATUS_RED_SECONDS = 25; // delay before flagging system as RED

    private EventBus eb = null;
    
    @Override
    public void start(Future<Void> fut) throws Exception
    {

        // load initialization values from config()
        if (!get_config())
              {
                  fut.fail("FeedPlayer: failed to load initial config()");
              }

        System.out.println("FeedPlayer: " + MODULE_NAME + "." + MODULE_ID + " started on " + FEEDPLAYER_ADDRESS);

        eb = vertx.eventBus();

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

        // asynchronously step through the filesystem, sending files as messages
        publish_files( START_TS, FINISH_TS );
        
      } // end start()


    // iterate through the filesystem, sending files as messages
    void publish_files(Long start_ts, Long finish_ts) throws Exception
    {
        Date d = new Date(start_ts * 1000);

        //debug does this pick up the timezone?
        String yyyymmdd =  new SimpleDateFormat("yyyy/MM/dd").format(d);

        process_gtfs_dir(start_ts, finish_ts, TFC_DATA_BIN+"/"+yyyymmdd);
    } // end publish_files()

    void process_gtfs_dir(long start_ts, Long finish_ts, String bin_path) throws Exception
    {

        System.out.println("FeedPlayer."+MODULE_ID+" processing "+bin_path);
        
        // check if date is already past finish_ts
        String yyyymmdd = get_date(bin_path+"/x");

        Date d = new SimpleDateFormat("yyyy/MM/dd").parse(yyyymmdd);
        
        //Date d = LocalDateTime.parse(yyyymmdd, DateTimeFormatter.ofPattern("yyyy/MM/dd"));

        Long dir_ts = d.getTime() / 1000; // unix timestamp is java millisecs / 1000

        // if directory is beyond required time, then end playback (i.e. do nothing & return)
        if (dir_ts > finish_ts)
            {
                System.out.println("FeedPlayer."+MODULE_ID+" ending, dir "+yyyymmdd+" later than finish timestamp");
                return;
            }
        
        // read list of days filenames from directory
        vertx.fileSystem().readDir(bin_path, res -> {
                if (res.succeeded())
                    {
                        // process the gtfs binary files, starting at file 0
                        try
                            {
                                // filenames are <UTC-TS>_YYYY_MM_DD_hh_mm_ss.bin
                                // with the hh_mm_ss in local time
                                
                                // sort the files from the directory into timestamp order
                                Collections.sort(res.result());

                                // skip forward to first file newer than start_ts
                                int file_index = 0;
                                while (file_index < res.result().size() &&
                                       start_ts > get_ts(res.result().get(file_index))
                                      )
                                    {
                                        file_index++;
                                    }
                                System.out.println("FeedPlayer: starting with "+bin_path+" file #"+file_index);
                                // process files starting with start_ts or newer
                                process_gtfs_files( res.result(), file_index, finish_ts);
                            }
                        catch (Exception e)
                            {
                                System.err.println("FeedPlayer: exception in process_gtfs_files() " + e.getMessage());
                            }
                    }
                else
                    {
                        System.err.println(res.cause());
                    }
            });
      } // end process_gtfs_dir()

        
    // Iterate through the list of files
    // Note this procedure is tail-recursive
    // i.e. the style is "process first file".. "set timer to process remaining files"
    void process_gtfs_files(List<String> files, int i, Long finish_ts) throws Exception
    {

        // test if we've reached end of files for current day
        if (i >= files.size())
            {
                try
                  {
                    // at end of files in current directory, so move on to next day
                    String yyyymmdd = get_date(files.get(0));
                    System.out.println("FeedPlayer."+MODULE_ID+": " + yyyymmdd + " file list completed");

                    DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd");

                    LocalDate current_date =  LocalDate.parse(yyyymmdd, dtf);

                    LocalDate next_date = current_date.plusDays(1); 

                    String next_yyyymmdd = next_date.format(dtf);

                    System.out.println("FeedPlayer."+MODULE_ID+": moving on to "+next_yyyymmdd);

                    // Recursive call to process_gtfs_dir, with next day as data directory
                    // Note we are passing first arg 'start_ts' as zero as it is not relevant except
                    // on the original call to process_gtfs_dir()
                    process_gtfs_dir(0, finish_ts, TFC_DATA_BIN+"/"+next_yyyymmdd);
                  }
                catch (Exception e)
                  {
                    System.out.println("FeedPlayer."+MODULE_ID+": exception in process_gtfs_files changing dir");
                    e.printStackTrace();
                  }
                return;
            }
        if (get_ts(files.get(i)) > finish_ts)
            {
                System.out.println("FilePlayer: "+MODULE_ID+" ending, file replay reached finish time "+finish_ts);
                return;
            }
        // process current file
        
        process_gtfs_file(files.get(i));

        // process remaining files
        vertx.setTimer(RATE, id -> {
                try
                    {
                        process_gtfs_files(files, i + 1, finish_ts);
                    }
                catch (Exception e)
                    {
                        System.err.println("FeedPlayer: "+MODULE_ID+" exception in process_gtfs_files()");
                    }
            });
    }
    
    // publish single file as message
    void process_gtfs_file(String filepath) throws Exception
    {
        // Read a file
        vertx.fileSystem().readFile(filepath, res -> {
                if (res.succeeded())
                {
                    try
                    {
                        String basename = get_basename(filepath);
                        String yyyymmdd = get_date(filepath);
                        
                        System.out.println("Feedplayer."+MODULE_ID+" publishing "+yyyymmdd+"/"+basename);
                        
                      JsonObject msg = GTFS.buf_to_json(res.result(), basename, yyyymmdd);

                      msg.put("module_name", MODULE_NAME);
                      msg.put("module_id", MODULE_ID);
                      msg.put("msg_type", Constants.FEED_BUS_POSITION);
        
                      eb.publish(FEEDPLAYER_ADDRESS, msg);
                      //System.out.println("FeedPlayer: ."+MODULE_ID+" published to "+FEEDPLAYER_ADDRESS);
                    } catch (Exception e)
                    {
                        System.err.println("FeedPlayer: exception in GTFS.buf_to_json()");
                    }
                } else
                {
                    System.err.println("FeedPlayer: " + res.cause());
                }
            });
        
    } // end process_gtfs_file()
  
    // pick out the Long timestamp embedded in the file name
    // e.g. <bin_path>/2016/03/07/1457334014_2016-03-07-07-00-14.bin -> 1457334014
    Long get_ts(String filepath)
    {

        // starting char index of timestamp (either index after last '/', or 0)
        int ts_start = filepath.lastIndexOf('/') + 1;

        // length of utc timestamp e.g. "1457334014" = 10
        int ts_length = filepath.lastIndexOf('_') - ts_start;

        // extract substring i.e. "1457334014"
        String ts_string = filepath.substring(ts_start, ts_start+ts_length);
        
        return Long.parseLong(ts_string);
    }

    // get base filename from filepath
    //  e.g. "<bin_path>/2016/03/07/1457334014_2016-03-07-07-00-14.bin" -> "1457334014_2016-03-07-07-00-14"
    String get_basename(String filepath)
    {
        String[] parts = filepath.split("/");

        String filename_bin = parts[parts.length - 1];

        int dot_index = filename_bin.lastIndexOf('.');

        return filename_bin.substring(0, dot_index);
    }

    // get YYYY/MM/DD from filepath
    //  e.g. "<bin_path>/2016/03/07/1457334014_2016-03-07-07-00-14.bin" -> "2016/03/07"
    String get_date(String filepath)
    {
        String [] parts = filepath.split("/");
        return parts[parts.length-4]+"/"+parts[parts.length-3]+"/"+parts[parts.length-2];
    }

    
    // Load initialization global constants defining this Zone from config()
    private boolean get_config()
    {
        // config() values needed by all TFC modules are:
        //   tfc.module_id - unique module reference to be used by this verticle
        //   eb.system_status - String eventbus address for system status messages

        MODULE_NAME = config().getString("module.name"); // "feedplayer"
        if (MODULE_NAME==null)
            {
                return false;
            }
        
        MODULE_ID = config().getString("module.id"); // A, B, ...

        EB_SYSTEM_STATUS = config().getString("eb.system_status");

        
        FEEDPLAYER_ADDRESS = config().getString(MODULE_NAME+".address"); // eventbus address to publish feed on

        //debug - this should be coming from a dynamic request, probably...
        TFC_DATA_BIN = config().getString(MODULE_NAME+".files");

        START_TS = config().getLong(MODULE_NAME+".start_ts");

        FINISH_TS = config().getLong(MODULE_NAME+".finish_ts"); //debug not used yet

        RATE = config().getInteger(MODULE_NAME+".rate");

        return true;
    }
    
} // end FeedPlayer class
