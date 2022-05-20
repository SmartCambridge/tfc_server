package uk.ac.cam.tfc_server.batcher;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// Batcher.java
// Version 0.04
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' next-generation Realtime Intelligent Traffic Analysis system
//
// Deploys BatcherWorker worker verticles to batch-process historical data
//
// Batcher can iterate the files in a given set of directories (assuming a yyyy/MM/dd directory
// structure) bounded by utc timestamps (it does this in the same way as FeedPlayer) and
// assumes file names have the format <utc timestamp>_yyyy_MM_dd_hh_mm_ss.<suffix>. The file
// iteration is actually done by BatcherWorker so see BatcherWorker.java to see how it actually
// operates.
//
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
import io.vertx.core.DeploymentOptions;

import java.io.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.text.SimpleDateFormat;
    
import uk.ac.cam.tfc_server.util.GTFS;
import uk.ac.cam.tfc_server.util.Constants;
import uk.ac.cam.tfc_server.util.Log;

// ********************************************************************************************
// ********************************************************************************************
// ********************************************************************************************
// Here is the main Batcher class definition
// ********************************************************************************************
// ********************************************************************************************
// ********************************************************************************************

public class Batcher extends AbstractVerticle {
    // Config vars
    private String MODULE_NAME; // from config()
    private String MODULE_ID; // from config()
    private String EB_SYSTEM_STATUS; // eventbus status reporting address

    private int LOG_LEVEL;
    
    private String BATCHER_ADDRESS; // eventbus address to talk to BatcherWorkers

    //debug not sure BatcherWorker module.name should be hardcoded
    String BW_MODULE_NAME = "batcherworker";
        
    private HashMap<String, BatcherWorkerConfig> BATCHERWORKERS; // optional from config(), list of workers to start
    
    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 15; // delay before flagging system as AMBER
    private final int SYSTEM_STATUS_RED_SECONDS = 25; // delay before flagging system as RED

    private final long MAXWORKER_NS = 120000000000L; // Max worker (i.e. batcherworker) execution time before Vertx complains
    
    private Log logger;
    
    private EventBus eb = null;
    
    @Override
    public void start() throws Exception
    {

        // load initialization values from config()
	if (!get_config())
        {
              Log.log_err("Batcher: failed to load initial config()");
              vertx.close();
              return;
        }

        logger = new Log(LOG_LEVEL);
        
        logger.log(Constants.LOG_DEBUG, "Batcher config()=");
        logger.log(Constants.LOG_DEBUG, config().toString());
        
        logger.log(Constants.LOG_INFO, "Batcher: " + MODULE_NAME + "." + MODULE_ID + " started on " + BATCHER_ADDRESS);

        eb = vertx.eventBus();

        for (String bw_id : BATCHERWORKERS.keySet())
            {
                deploy_batcherworker(BATCHERWORKERS.get(bw_id));
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

    // Deploy BatcherWorker as a WORKER verticle
    private void deploy_batcherworker(BatcherWorkerConfig bwc)
    {
        logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": deploying "+BW_MODULE_NAME+"."+bwc.MODULE_ID);
        logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": "+bwc.DATA_BIN+","+bwc.START_TS+","+bwc.FINISH_TS);

        // build config options for this BatcherWorker as Json object
        JsonObject conf = new JsonObject();

        conf.put("module.name", BW_MODULE_NAME);

        conf.put("module.id", bwc.MODULE_ID);

        conf.put("batcher.address", BATCHER_ADDRESS);

        conf.put(BW_MODULE_NAME+".log_level", bwc.LOG_LEVEL);

        conf.put(BW_MODULE_NAME+".msg_type", bwc.MSG_TYPE);

        conf.put(BW_MODULE_NAME+".data_bin", bwc.DATA_BIN);

        conf.put(BW_MODULE_NAME+".start_ts", bwc.START_TS);

        conf.put(BW_MODULE_NAME+".finish_ts", bwc.FINISH_TS);

        conf.put(BW_MODULE_NAME+".zones", bwc.ZONES);

        conf.put(BW_MODULE_NAME+".filers", bwc.FILERS);

        // Load config JsonObject into a DeploymentOptions object
        DeploymentOptions batcherworker_options = new DeploymentOptions().setConfig(conf);

        // set as WORKER verticle (i.e. synchronous, not non-blocking)
        batcherworker_options.setWorker(true);
        batcherworker_options.setWorkerPoolSize(5);
        batcherworker_options.setMaxWorkerExecuteTime(MAXWORKER_NS);
        long mwet = batcherworker_options.getMaxWorkerExecuteTime();
        //boolean mwet = batcherworker_options.isWorker();
        logger.log(Constants.LOG_DEBUG, "MaxWorkerExecuteTime="+mwet);

        // debug printing whole BatcherWorker config()
        logger.log(Constants.LOG_DEBUG, "Batcher: new BatcherWorker config() with worker and maxtime:");
        logger.log(Constants.LOG_DEBUG, batcherworker_options.toJson().toString());
        
        // note the BatcherWorker json config() file has MODULE_ID of this BATCHER
//        vertx.deployVerticle("service:uk.ac.cam.tfc_server.batcherworker."+bwc.MODULE_ID,
        vertx.deployVerticle("uk.ac.cam.tfc_server.batcher.BatcherWorker",
                             batcherworker_options,
                             res -> {
                if (res.succeeded()) {
                    logger.log(Constants.LOG_INFO, "Batcher."+MODULE_ID+": BatcherWorker "+bwc.MODULE_ID+ "started");
                } else {
                    System.err.println("Batcher."+MODULE_ID+": failed to start BatcherWorker " + bwc.MODULE_ID);
                }
            });
    }
    

    
    // Load initialization global constants defining this Zone from config()
    private boolean get_config()
    {
        // config() values needed by all TFC modules are:
        //   tfc.module_id - unique module reference to be used by this verticle
        //   eb.system_status - String eventbus address for system status messages

        MODULE_NAME = config().getString("module.name"); // "batcher"
        if (MODULE_NAME==null)
            {
                System.err.println("Batcher config() error: failed to load module.name");
                return false;
            }
        
        MODULE_ID = config().getString("module.id"); // A, B, ...
        if (MODULE_ID==null)
            {
                System.err.println(MODULE_NAME+" config() error: failed to load module.id");
                return false;
            }

        EB_SYSTEM_STATUS = config().getString("eb.system_status");
        if (EB_SYSTEM_STATUS==null)
            {
                System.err.println(MODULE_NAME+"."+MODULE_ID+" config() error: failed to load eb.system_status");
                return false;
            }

        LOG_LEVEL = config().getInteger(MODULE_NAME+".log_level", 0);
        if (LOG_LEVEL==0)
            {
                LOG_LEVEL = Constants.LOG_INFO;
            }
        
        BATCHER_ADDRESS = config().getString(MODULE_NAME+".address"); // eventbus address to publish feed on
        if (BATCHER_ADDRESS==null)
            {
                System.err.println(MODULE_NAME+"."+MODULE_ID+" config() error: failed to load "+MODULE_NAME+".address");
                return false;
            }

        // get list of BatchWorkers to start on startup
        BATCHERWORKERS = new HashMap<String, BatcherWorkerConfig>();

        JsonArray batcherworker_list = config().getJsonArray(MODULE_NAME+".batcherworkers");
        if (batcherworker_list != null)
            {
                                
                for (int i=0; i<batcherworker_list.size(); i++)
                    {
                        String batcherworker_id = batcherworker_list.getString(i);
                        
                        BatcherWorkerConfig bwc = new BatcherWorkerConfig(batcherworker_id);

                        bwc.LOG_LEVEL = LOG_LEVEL;

                        bwc.MSG_TYPE = config().getString(BW_MODULE_NAME+"."+batcherworker_id+".msg_type","gtfs_bin");
                        
                        bwc.DATA_BIN = config().getString(BW_MODULE_NAME+"."+batcherworker_id+".data_bin");

                        bwc.START_TS = config().getLong(BW_MODULE_NAME+"."+batcherworker_id+".start_ts");

                        bwc.FINISH_TS = config().getLong(BW_MODULE_NAME+"."+batcherworker_id+".finish_ts");

                        bwc.ZONES = new ArrayList<String>();
                        JsonArray zone_list = config().getJsonArray(BW_MODULE_NAME+"."+batcherworker_id+".zones");
                        if (zone_list!=null)
                            {
                                for (int j=0; j<zone_list.size(); j++)
                                    {
                                        bwc.ZONES.add(zone_list.getString(j));
                                    }
                            }

                        bwc.FILERS = config().getJsonArray(BW_MODULE_NAME+"."+batcherworker_id+".filers");

                        BATCHERWORKERS.put(batcherworker_id, bwc);
                    }
            }
        
        return true;
    }

    private class BatcherWorkerConfig {
        public String MODULE_ID; // id of worker module e.g. "A"
        public String MSG_TYPE;  // empty => gtfs, "sirivm_json" => SiriVM JSON
        public String DATA_BIN;  // path to root of bin files without ending '/'
        public String DATA_ZONE; // path to root of zone completion files without ending '/'
        public Long START_TS;    // unix timestamp of start of data
        public Long FINISH_TS;   // unix timestamp of end of data
        public ArrayList<String> ZONES;
        public JsonArray FILERS;
        public int LOG_LEVEL;

        public BatcherWorkerConfig(String id)
        {
            MODULE_ID = id;
        }
    } // end class BatchWorkerConfig
    
} // end Batcher class
