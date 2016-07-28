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
import uk.ac.cam.tfc_server.zone.ZoneConfig; // Config to be passed to Zone
import uk.ac.cam.tfc_server.zone.ZoneCompute; // BatcherWorker will call methods in Zone directly
import uk.ac.cam.tfc_server.msgfiler.FilerConfig; // BatcherWorker will instantiate FilerUtils
import uk.ac.cam.tfc_server.msgfiler.FilerUtils; // BatcherWorker will instantiate FilerUtils
import uk.ac.cam.tfc_server.util.IMsgHandler; // Interface for message handling in caller


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

    private String TFC_DATA_BIN; // root of bin input files
    private Long   START_TS;   // UTC timestamp for first position record file to publish
    private Long   FINISH_TS;  // UTC timestamp to end feed
    private ArrayList<String> ZONE_NAMES; // from config() MODULE_NAME.zones
    private ArrayList<FilerConfig> FILERS; // config() MODULE_NAME.filers parameters
    
    
    private HashMap<String, ZoneCompute> zones; // zones to run against bin gtfs records

    private ArrayList<FilerUtils> filers; // filers to call to store messages
    
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
        System.out.println(MODULE_NAME+"."+MODULE_ID+": time boundaries "+START_TS+","+FINISH_TS);
        System.out.println(MODULE_NAME+"."+MODULE_ID+": input bin files "+TFC_DATA_BIN);
        System.out.println(MODULE_NAME+"."+MODULE_ID+": zones "+ZONE_NAMES.toArray().toString());
        
        zones = create_zones(ZONE_NAMES, new MsgHandler());

        filers = create_filers(FILERS); // create list of FilerUtils from FilerConfig list

        eb = vertx.eventBus();

        // SYNCHRONOUSLY step through the filesystem, sending files as messages
        process_bin_files( START_TS, FINISH_TS );
        
      } // end start()

    // ************************************************************************
    // *************** create_zones( zone_list)   *****************************
    // **************  and create_zone( zone_id ) *****************************
    // ************************************************************************
    //
    // Given a list of strings containing the zone_id's
    // create a HashMap of zone_id -> ZoneCompute
    //
    HashMap<String, ZoneCompute> create_zones(ArrayList<String> zone_list, MsgHandler msg_handler)
    {
        HashMap<String, ZoneCompute> zc_list = new HashMap<String, ZoneCompute>();

        for (int i=0; i<zone_list.size(); i++)
            {
                String zone_id = zone_list.get(i);
        
                zc_list.put(zone_id, create_zone(zone_id, msg_handler));

                System.out.println(MODULE_NAME+"."+MODULE_ID+": ZoneCompute("+zone_id+") created");
            }
        return zc_list;
    }

    // create_zone
    //
    // read the zone_id json config and return a ZoneCompute for this zone_id
    ZoneCompute create_zone(String zone_id, MsgHandler msg_handler)
    {
    
        String json_path = "/uk.ac.cam.tfc_server.zone."+zone_id+".json";
        
        StringBuffer sb = new StringBuffer();
        try {
                BufferedReader br = new BufferedReader(
                                     new InputStreamReader(
                                      getClass().getResourceAsStream(json_path),
                                      "UTF-8"));
                for (int c = br.read(); c != -1; c = br.read()) sb.append((char)c);
        } catch (Exception e)
            {
                System.err.println(MODULE_NAME+"."+MODULE_ID+": Exception reading zone config "+json_path);
            }
        
        JsonObject json_config = (new JsonObject(sb.toString()))
                                    .getJsonObject("options")
                                    .getJsonObject("config");

        ZoneConfig zone_config = new ZoneConfig(json_config);

        return new ZoneCompute(zone_config, msg_handler);

    }

    // ************************************************************************
    // *************** create_filers( filerconfig_list)   *********************
    // ************************************************************************
    //
    // Given a list of FilerConfigs create an ArrayList of FilerUtils
    //
    ArrayList create_filers(ArrayList<FilerConfig> filerconfig_list)
    {
        ArrayList filer_list = new ArrayList<FilerUtils>();

        for (int i=0; i<filerconfig_list.size(); i++)
            {
                filer_list.add( new FilerUtils(vertx, filerconfig_list.get(i)) );
            }
        return filer_list;
    }

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
                
                String yyyymmdd =  zoned_datetime.format(formatter);

                System.out.println(MODULE_NAME+"."+MODULE_ID+": processing date "+yyyymmdd);

                // iterate through current bin file directory
                process_bin_dir(next_start_ts, finish_ts, TFC_DATA_BIN+"/"+yyyymmdd);
                
                ZonedDateTime next_day = zoned_datetime.plusDays(1L).withHour(0).withMinute(0).withSecond(0); // add a day

                next_start_ts = next_day.toEpochSecond();

            }

        System.out.println("finished at "+next_start_ts);

    } // end process_bin_files()

    // iterate through bin files in directory <bin_path>
    void process_bin_dir(long start_ts, Long finish_ts, String bin_path) throws Exception
    {

        //System.out.println("BatcherWorker."+MODULE_ID+" processing "+bin_path);
        
        List<Path> file_paths = Files.walk(Paths.get(bin_path))
            .filter(Files::isRegularFile)
            .collect(Collectors.toList());

        Collections.sort(file_paths);
        
        file_paths.forEach(file_path -> {
        
                // filenames are <UTC-TS>_YYYY_MM_DD_hh_mm_ss.bin
                // with the hh_mm_ss in local time

                // get UTC timestamp from filename
                Long file_ts = get_ts(file_path.toString());
                
                if (start_ts < file_ts && finish_ts > file_ts)
                    {
                        try
                            {
                                process_gtfs_file(file_path);
                            }
                        catch (Exception e)
                            {
                                System.err.println(MODULE_NAME+"."+MODULE_ID+
                                                       ": process_gtfs_file exception "+file_path.toString());
                            }
                    }
        
            });
        
      } // end process_gtfs_dir()

    // process single gtfs binary file
    void process_gtfs_file(Path file_path) throws Exception
    {
        Buffer file_data;
        
        // Read the file
        try
            {
                file_data = vertx.fileSystem().readFileBlocking(file_path.toString());
            }
        catch (Exception e)
            {
                System.err.println(MODULE_NAME+"."+MODULE_ID+": error reading "+file_path.toString());
                e.printStackTrace();
                return;
            }

        try
        {
            String fs = file_path.toString();
            String basename = get_basename(fs);
            String yyyymmdd = get_date(fs);

            //System.out.println(MODULE_NAME+"."+MODULE_ID+": processing gtfs file "+yyyymmdd+"/"+basename);

            JsonObject msg = GTFS.buf_to_json(file_data, basename, yyyymmdd);

            msg.put("module_name", MODULE_NAME);
            msg.put("module_id", MODULE_ID);
            msg.put("msg_type", Constants.FEED_BUS_POSITION);

            // Here is where we pass the current feed data through the configured zones
            for (String zone_id: zones.keySet())
                {
                    zones.get(zone_id).handle_feed(msg);
                }

          //eb.publish(FEEDPLAYER_ADDRESS, msg);
          //System.out.println("BatcherWorker: ."+MODULE_ID+" published to "+FEEDPLAYER_ADDRESS);
        } catch (Exception e)
        {
            System.err.println(MODULE_NAME+"."+MODULE_ID+": exception processing gtfs file "+file_path.toString());
        }
        
    } // end process_gtfs_file()
  
    // pick out the Long timestamp embedded in the file name
    // e.g. <bin_path>/2016/03/07/1457334014_2016-03-07-07-00-14.bin -> 1457334014
    Long get_ts(String fs)
    {
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
    String get_basename(String fs)
    {
        String[] parts = fs.split("/");

        String filename_bin = parts[parts.length - 1];

        int dot_index = filename_bin.lastIndexOf('.');

        return filename_bin.substring(0, dot_index);
    }

    // get YYYY/MM/DD from filepath
    //  e.g. "<bin_path>/2016/03/07/1457334014_2016-03-07-07-00-14.bin" -> "2016/03/07"
    String get_date(String fs)
    {
        String [] parts = fs.split("/");
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

        TFC_DATA_BIN = config().getString(MODULE_NAME+".data_bin");
        if (TFC_DATA_BIN==null)
            {
                System.err.println(MODULE_NAME+"."+MODULE_ID+" config() error: failed to load "+MODULE_NAME+".data_bin");
                return false;
            }


        START_TS = config().getLong(MODULE_NAME+".start_ts");

        FINISH_TS = config().getLong(MODULE_NAME+".finish_ts");

        ZONE_NAMES = new ArrayList<String>();
        
        JsonArray zone_list = config().getJsonArray(MODULE_NAME+".zones");
        System.out.println(zone_list.toString());
        if (zone_list!=null)
            {
                for (int j=0; j<zone_list.size(); j++)
                    {
                        ZONE_NAMES.add(zone_list.getString(j));
                    }
            }
        
        // iterate through the MODULE_NAME.filers config values
        FILERS = new ArrayList<FilerConfig>();
        JsonArray config_filer_list = config().getJsonArray(MODULE_NAME+".filers");
        for (int i=0; i<config_filer_list.size(); i++)
            {
                JsonObject config_json = config_filer_list.getJsonObject(i);

                // add MODULE_NAME, MODULE_ID to every FilerConfig
                config_json.put("module_name", MODULE_NAME);
                config_json.put("module_id", MODULE_ID);
                
                FilerConfig filer_config = new FilerConfig(config_json);
                
                FILERS.add(filer_config);
            }

        return true;
    }

    //*************************************************************************************
    // Class MsgHandler
    //*************************************************************************************
    //
    // passed to ZoneCompute for callback to handle zone event messages
    //
    class MsgHandler implements IMsgHandler {

        // general handle_msg function, called by ZoneCompute
        public void handle_msg(JsonObject msg)
        {
            // Pass this message to each of the Filers
            System.out.println(MODULE_NAME+"."+MODULE_ID+" storing "+msg.toString());
            for (int i=0; i<filers.size(); i++)
                {
                    filers.get(i).store_msgBlocking(msg);
                }
        }

    } // end class MsgHandler
    

} // end BatcherWorker class
