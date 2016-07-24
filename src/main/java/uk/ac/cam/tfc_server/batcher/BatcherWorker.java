package uk.ac.cam.tfc_server.batcher;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// BatcherWorker.java
// Version 0.01
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' next-generation Realtime Intelligent Traffic Analysis system
//
// Designed to run in non-interactive 'batch' mode, primarily for retrospective processing of
// historical position data, e.g. to run the data through Zones to produce the transit-time data.
//
// Reads GTFS-format binary files from the filesystem, Zones will write corresponding transit data.
//
// BatcherWorker is similar to FeedPlayer, *without* the requirement to broadcast the feed data onto
// the message bus.  The 'synchronous' interlocking of the data processing allows the processing
// to proceed as fast as possible.
//
// For the initial version of BatcherWorker the obvious uses are Zone and FeedCSV processing.
//
// BatcherWorker is designed to be deployed by Batcher
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
import java.nio.file.*;
import java.util.stream.Collectors;

import uk.ac.cam.tfc_server.util.GTFS;
import uk.ac.cam.tfc_server.util.Constants;

// ********************************************************************************************
// ********************************************************************************************
// ********************************************************************************************
// Here is the main BatcherWorker class definition
// ********************************************************************************************
// ********************************************************************************************
// ********************************************************************************************

public class BatcherWorker extends AbstractVerticle {
    // Config vars
    private String MODULE_NAME; // from config()
    private String MODULE_ID; // from config()

    private String BATCHER_ADDRESS; // eventbus address to communicate with Batcher controller

    private String TFC_DATA_BIN; // root of bin files
    private Long   START_TS;   // UTC timestamp for first position record file to publish
    private Long   FINISH_TS;  // UTC timestamp to end feed
    
    private EventBus eb = null;
    
    @Override
    public void start(Future<Void> fut) throws Exception
    {

        // load initialization values from config()
        if (!get_config())
              {
                  fut.fail("BatcherWorker: failed to load initial config()");
              }

        System.out.println(MODULE_NAME+"."+MODULE_ID+": started on " + BATCHER_ADDRESS);
        System.out.println(MODULE_NAME+"."+MODULE_ID+": "+TFC_DATA_BIN+","+START_TS+","+FINISH_TS);
        
        eb = vertx.eventBus();

        // SYNCHRONOUSLY step through the filesystem, sending files as messages
        process_bin_files( START_TS, FINISH_TS );
        
      } // end start()


    // iterate through the filesystem, processing files between start_ts and finish_ts
    void process_bin_files(Long start_ts, Long finish_ts) throws Exception
    {
        // next_start_ts will increment through the days, starting with start_ts
        Long next_start_ts = start_ts;
        
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd");

        while (next_start_ts < finish_ts)
            {
                Instant i = Instant.ofEpochSecond(next_start_ts); // convert UNIX ts to java Instant

                ZonedDateTime zoned_datetime = i.atZone(ZoneId.systemDefault()); // convert Instant to local Date
                
                System.out.println("start_ts " + zoned_datetime.toString());


                //debug does conversion of start_ts to yyyy/MM/dd this pick up the local timezone?
                String yyyymmdd =  zoned_datetime.format(formatter);

                System.out.println("yyyymmdd "+yyyymmdd);

                // iterate through current bin file directory
                process_bin_dir(next_start_ts, finish_ts, TFC_DATA_BIN+"/"+yyyymmdd);
                
                ZonedDateTime next_day = zoned_datetime.plusDays(1L).withHour(0).withMinute(0).withSecond(0); // add a day

                System.out.println("next_day " + next_day.toString());

                System.out.println("next_yyyymmdd " + next_day.format(formatter));

                next_start_ts = next_day.toEpochSecond();

                System.out.println("next_day timestamp "+next_start_ts);
        
            }

        System.out.println("finished at "+next_start_ts);

    } // end process_bin_files()

    // iterate through bin files, starting at bin_path
    void process_bin_dir(long start_ts, Long finish_ts, String bin_path) throws Exception
    {

        System.out.println("BatcherWorker."+MODULE_ID+" processing "+bin_path);
        
        List<Path> file_paths = Files.walk(Paths.get(bin_path))
            .filter(Files::isRegularFile)
            .collect(Collectors.toList());

        Collections.sort(file_paths);
        
        file_paths.forEach(file_path -> {
        
                // filenames are <UTC-TS>_YYYY_MM_DD_hh_mm_ss.bin
                // with the hh_mm_ss in local time

                // get UTC timestamp from filename
                Long file_ts = get_ts(file_path);
                
                if (start_ts < file_ts && finish_ts > file_ts)
                    {
                        System.out.println(file_path);
                    }
        
            });
        
        /*
        // read list of days filenames from directory
        vertx.fileSystem().readDir(bin_path, res -> {
                if (res.succeeded())
                    {
                        // process the gtfs binary files, starting at file 0
                        try
                            {
                                
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
                                System.out.println("BatcherWorker: starting with "+bin_path+" file #"+file_index);
                                // process files starting with start_ts or newer
                                process_gtfs_files( res.result(), file_index, finish_ts);
                            }
                        catch (Exception e)
                            {
                                System.err.println("BatcherWorker: exception in process_gtfs_files() " + e.getMessage());
                            }
                    }
                else
                    {
                        System.err.println(res.cause());
                    }
            });
        */
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
                    System.out.println("BatcherWorker."+MODULE_ID+": " + yyyymmdd + " file list completed");

                    DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd");

                    LocalDate current_date =  LocalDate.parse(yyyymmdd, dtf);

                    LocalDate next_date = current_date.plusDays(1); 

                    String next_yyyymmdd = next_date.format(dtf);

                    System.out.println("BatcherWorker."+MODULE_ID+": moving on to "+next_yyyymmdd);

                    // Recursive call to process_gtfs_dir, with next day as data directory
                    // Note we are passing first arg 'start_ts' as zero as it is not relevant except
                    // on the original call to process_gtfs_dir()
                    process_bin_dir(0, finish_ts, TFC_DATA_BIN+"/"+next_yyyymmdd);
                  }
                catch (Exception e)
                  {
                    System.out.println("BatcherWorker."+MODULE_ID+": exception in process_gtfs_files changing dir");
                    e.printStackTrace();
                  }
                return;
            }
        /*
        if (get_ts(files.get(i)) > finish_ts)
            {
                System.out.println("FilePlayer: "+MODULE_ID+" ending, file replay reached finish time "+finish_ts);
                return;
            }
        */
        // process current file
        
        process_gtfs_file(files.get(i));

        // process remaining files
        vertx.setTimer(1000, id -> {
                try
                    {
                        process_gtfs_files(files, i + 1, finish_ts);
                    }
                catch (Exception e)
                    {
                        System.err.println("BatcherWorker: "+MODULE_ID+" exception in process_gtfs_files()");
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
                        
                        System.out.println("BatcherWorker."+MODULE_ID+" publishing "+yyyymmdd+"/"+basename);
                        
                      JsonObject msg = GTFS.buf_to_json(res.result(), basename, yyyymmdd);

                      msg.put("module_name", MODULE_NAME);
                      msg.put("module_id", MODULE_ID);
                      msg.put("msg_type", Constants.FEED_BUS_POSITION);
        
                      //eb.publish(FEEDPLAYER_ADDRESS, msg);
                      //System.out.println("BatcherWorker: ."+MODULE_ID+" published to "+FEEDPLAYER_ADDRESS);
                    } catch (Exception e)
                    {
                        System.err.println("BatcherWorker: exception in GTFS.buf_to_json()");
                    }
                } else
                {
                    System.err.println("BatcherWorker: " + res.cause());
                }
            });
        
    } // end process_gtfs_file()
  
    // pick out the Long timestamp embedded in the file name
    // e.g. <bin_path>/2016/03/07/1457334014_2016-03-07-07-00-14.bin -> 1457334014
    Long get_ts(Path filepath)
    {

        String fs = filepath.toString();
        
        // starting char index of timestamp (either index after last '/', or 0)
        int ts_start = fs.lastIndexOf('/') + 1;

        // length of utc timestamp e.g. "1457334014" = 10
        int ts_length = fs.lastIndexOf('_') - ts_start;

        // extract substring i.e. "1457334014"
        String ts_string = fs.substring(ts_start, ts_start+ts_length);
        
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

        //debug printing whole config()
        System.out.println("BatcherWorker config()=");
        System.out.println(config().toString());
        
        MODULE_NAME = config().getString("module.name"); // "batcherworker"
        if (MODULE_NAME==null)
            {
                System.err.println("BatcherWorker config() error: failed to load module.name");
                return false;
            }
        
        MODULE_ID = config().getString("module.id"); // A, B, ...
        if (MODULE_ID==null)
            {
                System.err.println(MODULE_NAME+" config() error: failed to load module.id");
                return false;
            }

        BATCHER_ADDRESS = config().getString("batcher.address"); // eventbus address for control from Batcher
        if (BATCHER_ADDRESS==null)
            {
                System.err.println(MODULE_NAME+"."+MODULE_ID+" config() error: failed to load batcher.address");
                return false;
            }
        //debug - this should be coming from a dynamic request, probably...
        TFC_DATA_BIN = config().getString(MODULE_NAME+".files");

        System.out.println(MODULE_NAME+"."+MODULE_ID+": readind config() "+MODULE_NAME+".start_ts");
        START_TS = config().getLong(MODULE_NAME+".start_ts");
        System.out.println(MODULE_NAME+"."+MODULE_ID+": read config() "+MODULE_NAME+".start_ts="+START_TS);

        FINISH_TS = config().getLong(MODULE_NAME+".finish_ts");

        return true;
    }
    
} // end BatcherWorker class
