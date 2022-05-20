package uk.ac.cam.tfc_server.msgrouter;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// MsgRouter.java
//
// This module receives messages from the EventBus and POSTs them on to application destinations
//
// Author: Ian Lewis ijl20@cam.ac.uk
//
// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

import io.vertx.ext.jdbc.JDBCClient;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.sql.ResultSet;

import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;

import java.io.*;
import java.net.URL;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;

import uk.ac.cam.tfc_server.util.Constants;
import uk.ac.cam.tfc_server.util.Log;

public class MsgRouter extends AbstractVerticle {

    private final String VERSION = "0.21";

    // from config()
    public int LOG_LEVEL;             // optional in config(), defaults to Constants.LOG_INFO
    private String MODULE_NAME;       // config module.name - normally "msgrouter"
    private String MODULE_ID;         // config module.id - unique for this verticle
    private String EB_SYSTEM_STATUS;  // config eb.system_status
    private String EB_MANAGER;        // config eb.manager

    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 25;
    private final int SYSTEM_STATUS_RED_SECONDS = 35;

    private EventBus eb = null;
    private Log logger;

    private ArrayList<JsonObject> START_ROUTERS; // config msgrouters.routers parameters

    // global vars
    private HashMap<String,WebClient> web_clients; // used to store a WebClient for each feed_id

    private Destinations destinations; // stores destination_type->destination_id -> http POST mapping

    private Sensors sensors; // stores sensor_type-> sensor_id -> destination_type/id mapping

    @Override
    public void start() throws Exception
    {

        // load initialization values from config()
        if (!get_config())
            {
                Log.log_err("MsgRouter."+ MODULE_ID + ": failed to load initial config()");
                vertx.close();
                return;
            }

        logger = new Log(LOG_LEVEL);

        logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": Version "+VERSION+" started with log_level "+LOG_LEVEL);

        eb = vertx.eventBus();

        // create holder for WebClients, one per router
        web_clients = new HashMap<String,WebClient>();

        // create holders for sensor and application data
        sensors = new Sensors();
        destinations = new Destinations();

        // Asynchronous load of sensor and destination data from PostgreSQL
        load_data();

        // iterate through all the routers to be started
        for (int i=0; i<START_ROUTERS.size(); i++)
            {
                start_router(START_ROUTERS.get(i));
            }

        // **********************************************************************************
        // Subscribe to 'manager' messages, e.g. to add sensors and destinations
        //
        // For the message to be processed by this module, it must be sent with this module's
        // MODULE_NAME and MODULE_ID in the "to_module_name" and "to_module_id" fields. E.g.
        //{       "module_name":"httpmsg",
        //        "module_id":"test"
        //        "to_module_name":"msgrouter",
        //        "to_module_id":"test",
        //        "method":"add_sensor",
        //        "params":{ "info": { "sensor_id": "abc",
        //                "sensor_type": "lorawan",
        //                "destination_id": "xyz",
        //                "destination_type": "everynet_jsonrpc"
        //                }
        //    }
        //}
        logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+
                   ": starting listener for manager messages on "+EB_MANAGER);

        eb.consumer(EB_MANAGER, message -> {
                manager_message(message);
            });

        // **********************************************************************************
        // send system status message from this module (i.e. to itself) immediately on startup, then periodically
        send_status();
        // send periodic "system_status" messages
        vertx.setPeriodic(SYSTEM_STATUS_PERIOD, id -> { send_status();  });

    } // end start()

    // This procedure loads data from the PostgreSQL database asynchronously via vertx.executeBlocking
    // Note the procedure will return *immediately* due to it's asynchronous call
    private void load_data()
    {
        vertx.executeBlocking(promise -> {
                    load_data_sql(promise);
                },
                res -> {
                    if (res.succeeded())
                    {
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                  " load_data() complete, status "+res.result());
                    }
                    else
                    {
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                  " load_data() failed, status "+res.cause());
                    }
                       });

    }

    private boolean load_data_sql(Promise<Object> promise)
    {
        String db_user = config().getString(MODULE_NAME+".db.user");

        // MsgRouter may have ONLY destinations hard-coded into the config, in which case
        // we may not be using a Postgresql mapping table for sensor->destination
        if (db_user==null)
        {
            return false;
        }

        // Ok we have a 'db_user' so assume we need to connect to Postgresql and initialise sensor->destination tables

        JsonObject sql_client_config = new JsonObject()
              .put("url", config().getString(MODULE_NAME+".db.url"))
              .put("user", db_user)
              .put("password", config().getString(MODULE_NAME+".db.password"))
              .put("driver_class", "org.postgresql.Driver");

        //SQLClient sql_client = PostgreSQLClient.createShared(vertx, sql_client_config);
        JDBCClient jdbc_client = JDBCClient.createShared(vertx, sql_client_config);

        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                   ": load_data jdbc_client created for user "+db_user+" connecting to "+sql_client_config.getString("url"));

        jdbc_client.getConnection(res -> {
            if (res.failed())
            {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                            ": load_data getConnection failed.");
                promise.fail(res.cause());
            }
            else
            {
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                           ": load_data getConnection succeeded.");

                SQLConnection sql_connection = res.result();

                sql_connection.query( "SELECT info FROM csn_destination",
                     rd -> {
                              if (rd.failed())
                              {
                                  logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                                               ": Failed query SELECT info FROM csn_destination");
                                  promise.fail(rd.cause());
                                  return;
                              }

                              logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                         ": "+rd.result().getNumRows() + " rows returned from csn_destination");

                              int destination_count = 0;
                              for (JsonObject row : rd.result().getRows())
                              {
                                  //logger.log(Constants.LOG_DEBUG, row.toString());
                                  destinations.put(new JsonObject(row.getString("info")));
                                  destination_count++;
                              }


                              logger.log(Constants.LOG_DEBUG, MODULE_NAME+
                                         ": "+destination_count+" destinations loaded, "+destinations.type_count()+" type(s)");

                              sql_connection.query( "SELECT info FROM csn_sensor",
                                                    rs -> {
                                                        if (rs.failed())
                                                            {
                                                                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                                                                            ": Failed query SELECT info FROM csn_sensor");
                                                                promise.fail(rs.cause());
                                                                return;
                                                            }

                                                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                                                   ": "+rs.result().getNumRows() + " rows returned from csn_sensor");

                                                        // Accumulate count of sensors as they're added
                                                        int sensor_count = 0;

                                                        for (JsonObject row : rs.result().getRows())
                                                            {
                                                                //logger.log(Constants.LOG_DEBUG, row.toString());
                                                                sensors.put(new JsonObject(row.getString("info")));
                                                                sensor_count++;
                                                            }

                                                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+
                                                                           ": "+sensor_count+" sensors loaded, "+sensors.type_count()+" type(s)");

                                                        // close connection to database
                                                        sql_connection.close(v -> {
                                                                logger.log(Constants.LOG_DEBUG, MODULE_NAME+
                                                                           ": sql_connection closed.");
                                                                promise.complete("ok");
                                                            });
                                                    });
                          });
            }
        });

        return true;
    }

    // Here is where we process the 'manager' messages received for this module on the
    // config 'eb.manager' eventbus address.
    // e.g. the 'add_sensor' and 'add_application' messages.
    private void manager_message(Message<java.lang.Object> message)
    {
        JsonObject msg = new JsonObject(message.body().toString());

        // For debug purposes, display any manage message on console.
        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                   ": detected manager message ");
        logger.log(Constants.LOG_DEBUG, msg.toString());

        // decode who this 'manager' message was sent to
        String to_module_name = msg.getString("to_module_name");
        String to_module_id = msg.getString("to_module_id");

        // *********************************************************************************
        // Skip this message if it has the wrong module_name/module_id
        if (to_module_name == null || !(to_module_name.equals(MODULE_NAME)))
        {
            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                       ": skipping manager message (not for this module_name) on "+EB_MANAGER);
            return;
        }
        if (to_module_id == null || !(to_module_id.equals(MODULE_ID)))
        {
            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                       ": skipping manager message (not for this module_id) on "+EB_MANAGER);
            return;
        }

        // *********************************************************************************
        // Process the manager message

        // ignore the message if it has no 'method' property
        String method = msg.getString("method");
        if (method == null)
        {
            logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                       ": skipping manager message ('method' property missing) on "+EB_MANAGER);
            return;
        }

        switch (method)
        {
            case Constants.METHOD_ADD_SENSOR:
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                           ": received add_sensor manager message on "+EB_MANAGER);
                JsonObject sensor_info = msg.getJsonObject("params").getJsonObject("info");
                if (sensor_info == null)
                {
                    logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                               ": skipping manager message ('params' property missing) on "+EB_MANAGER);
                    return;
                }
                sensors.put(sensor_info);
                break;

            case Constants.METHOD_REMOVE_SENSOR:
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                           ": received remove_sensor manager message on "+EB_MANAGER);
                sensor_info = msg.getJsonObject("params").getJsonObject("info");
                if (sensor_info == null)
                {
                    logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                               ": skipping manager message ('params' property missing) on "+EB_MANAGER);
                    return;
                }
                sensors.remove(sensor_info);
                break;

            case Constants.METHOD_ADD_DESTINATION:
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                           ": received add_destination manager message on "+EB_MANAGER);
                JsonObject destination_info = msg.getJsonObject("params").getJsonObject("info");
                if (destination_info == null)
                {
                    logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                               ": skipping manager message ('params' property missing) on "+EB_MANAGER);
                    return;
                }
                destinations.put(destination_info);
                break;

            case Constants.METHOD_REMOVE_DESTINATION:
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                           ": received remove_destination manager message on "+EB_MANAGER);
                destination_info = msg.getJsonObject("params").getJsonObject("info");
                if (destination_info == null)
                {
                    logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                               ": skipping manager message ('params' property missing) on "+EB_MANAGER);
                    return;
                }
                destinations.remove(destination_info);
                break;

            //debug
            case "print_destinations":
                destinations.print();
                break;

            //debug
            case "print_sensors":
                sensors.print();
                break;

            //debug
            case "load_data":
                load_data();
                break;

            default:
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                           ": received unrecognized 'method' in manager message on "+EB_MANAGER+": "+method);
                break;
        }

    }

    // send UP status to the EventBus
    private void send_status()
    {
        eb.publish(EB_SYSTEM_STATUS,
                 "{ \"module_name\": \""+MODULE_NAME+"\"," +
                   "\"module_id\": \""+MODULE_ID+"\"," +
                   "\"status\": \"UP\"," +
                   "\"status_msg\": \"UP\"," +
                   "\"status_amber_seconds\": "+String.valueOf( SYSTEM_STATUS_AMBER_SECONDS ) + "," +
                   "\"status_red_seconds\": "+String.valueOf( SYSTEM_STATUS_RED_SECONDS ) +
                 "}" );
    }

    // ************************************************************
    // start_router()
    // start a Router by registering a consumer to the given address
    // ************************************************************
    private void start_router(JsonObject router_config)
    {

        // A router config() contains a minimum of a "source_address" property,
        // which is the EventBus address it will listen to for messages to be forwarded.
        //
        // Note: a router config() (in msgrouter.routers) MAY contain a filter, such as
        //        {
        //            "source_address": "tfc.everynet_feed.test",
        //            "source_filter": {
        //                                 "field": "dev_eui",
        //                                 "compare": "=",
        //                                 "value": "0018b2000000113e"
        //                             },
        //            "destination_id":    "test",
        //            "destination_type":  "everynet_jsonrpc",
        //            "url" :  "http://localhost:8080/everynet_feed/test/adeunis_test2",
        //            "http_token": "test-msgrouter-post"
        //            "http_token_header": "x-api-key"    // defaults to "X-Auth-Token"
        //        },
        //        {
        //            "source_address": "tfc.everynet_feed.test",
        //            "source_filter": {
        //                                 "field": "sensor_type",
        //                                 "compare": "=",
        //                                 "value": "lorawan"
        //                             },
        //        },
        //
        // in which case only messages on the source_address that match this pattern will
        // be processed.

        final JsonObject filter_json = router_config.getJsonObject("source_filter");
        final boolean has_filter =  filter_json != null;

        final RouterFilter source_filter = has_filter ? new RouterFilter(filter_json) : null;

        final boolean has_destination = destinations.put(router_config);

        String router_filter_text;
        if (has_filter)
            {
                //source_filter = new RouterFilter(filter_json);
                router_filter_text = " with filter " + filter_json.toString();
            }
        else
            {
                router_filter_text = " no filters ";
            }
        logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+
                   ": starting router "+router_config.getString("source_address")+ router_filter_text +
                   (has_destination ? " with destination " : " no destination "));

        // register to router_config.source_address,
        // test messages with router_config.source_filter
        // and call store_msg if current message passes filter
        eb.consumer(router_config.getString("source_address"), message -> {
            //System.out.println("MsgRouter."+MODULE_ID+": got message from " + router_config.source_address);
            JsonObject msg = new JsonObject(message.body().toString());

            //**************************************************************************
            //**************************************************************************
            // Route the message onwards via POST to destination
            //**************************************************************************
            //**************************************************************************
            if (!has_filter || source_filter.match(msg))
            {
                // route this message if it matches the filter within the RouterConfig
                //route_msg(web_client, router_config, msg);
                if (has_destination)
                {
                    String destination_type = router_config.getString("destination_type");
                    String destination_id = router_config.getString("destination_id");
                    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                               ": sending message to type/id: "+destination_type+"/"+destination_id+", url="+router_config.getString("url"));
                    try
                    {
                        switch (destination_type)
                        {
                            case Constants.FEED_EVENTBUS_MSG:
                                Destination d = destinations.get(destination_type, destination_id);

                                String send_msg = msg.toString();
                                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                           ": (destination "+ (d!=null ? "ok)":"null)")+" sending message");// \n"+send_msg);
                                d.send(send_msg);
                                break;

                            case Constants.FEED_EVENTBUS_0:
                                // Careful here!! Although FeedHandler(etc) can send an Array of data points in
                                // the "request_data" parameter, for LoraWAN purposes we are currently assuming
                                // only a single data value is going to be present, hence we are forwarding
                                // msg.getJsonArray("request_data").getJsonObject(0), not the whole array.
                                destinations.get(destination_type,destination_id).send(msg.getJsonArray("request_data").getJsonObject(0).toString());
                                break;

                            default:
                                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                                           ": start_router unrecognized destination_type in message "+destination_type+"/"+destination_id);
                                break;
                        }

                    }
                    catch (Exception e)
                    {
                        logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                                   ": send error for "+destination_type+"/"+destination_id);
                        e.printStackTrace();
                    }
                    return;
                }
                else
                {
                    // There is no destination_type/id defined in the config(), so we'll try and route via
                    // the sensor_type/id -> destination_id mapping in the sensors HashMap
                    String msg_sensor_id = msg.getString("sensor_id");
                    //debug! We will need to put this sensor type into a Constant
                    String msg_sensor_type = msg.getString("sensor_type");
                    if (msg_sensor_id == null || msg_sensor_type == null)
                    {
                        logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                                   ": skipping message (no sensor_id or sensor_type) ");
                        return;
                    }
                    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                               ": handling sensor data from "+msg_sensor_type+"/"+msg_sensor_id);

                    //debug! Need to re-do this key construction
                    String destination_id = null;
                    String destination_type = null;

                    try
                    {
                        // Here we pick out the
                        destination_id = (sensors.get(msg_sensor_type,msg_sensor_id).info).getString("destination_id");
                        destination_type = (sensors.get(msg_sensor_type,msg_sensor_id).info).getString("destination_type");
                    }
                    catch (Exception NullPointerException)
                    {
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                   ": ignoring sensor data from "+msg_sensor_type+"/"+msg_sensor_id+" no entry for sensor in in-memory cache");
                        return;
                    }

                    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                               ": sending "+msg_sensor_type+"/"+msg_sensor_id+" to "+destination_type+"/"+destination_id);

                    try
                    {
                        destinations.get(destination_type,destination_id)
                            .send(msg.getJsonArray("request_data").getJsonObject(0).toString());
                    }
                    catch (Exception NullPointerException)
                    {
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                   ": ignoring sensor data from "+msg_sensor_type+"/"+msg_sensor_id+" invalid destination in in-memory cache");
                        return;
                    }
                }
            }
            else
            {
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                           ": "+msg.getString("sensor_type")+"/"+msg.getString("sensor_id")+" msg skipped - no match "+
                           router_config.getJsonObject("source_filter").toString());
            }

        });

    } // end start_router

    //**************************************************************************
    //**************************************************************************
    // Load initialization global constants defining this MsgRouter from config()
    //**************************************************************************
    //**************************************************************************
    private boolean get_config()
    {
        // config() values needed by all TFC modules are:
        //   module.name - usually "msgrouter"
        //   module.id - unique module reference to be used by this verticle
        //   eb.system_status - String eventbus address for system status messages
        //   eb.manager - eventbus address for manager messages

        MODULE_NAME = config().getString("module.name");
        if (MODULE_NAME == null)
        {
          Log.log_err("MsgRouter: module.name config() not set");
          return false;
        }

        MODULE_ID = config().getString("module.id");
        if (MODULE_ID == null)
        {
          Log.log_err("MsgRouter: module.id config() not set");
          return false;
        }

        LOG_LEVEL = config().getInteger(MODULE_NAME+".log_level", 0);
        if (LOG_LEVEL==0)
            {
                LOG_LEVEL = Constants.LOG_INFO;
            }

        EB_SYSTEM_STATUS = config().getString("eb.system_status");
        if (EB_SYSTEM_STATUS == null)
        {
          Log.log_err("MsgRouter."+MODULE_ID+": eb.system_status config() not set");
          return false;
        }

        EB_MANAGER = config().getString("eb.manager");
        if (EB_MANAGER == null)
        {
          Log.log_err("MsgRouter."+MODULE_ID+": eb.manager config() not set");
          return false;
        }

        // iterate through the msgrouter.routers config values
        START_ROUTERS = new ArrayList<JsonObject>();
        JsonArray config_router_list = config().getJsonArray(MODULE_NAME+".routers");
        for (int i=0; i<config_router_list.size(); i++)
            {
                JsonObject config_json = config_router_list.getJsonObject(i);

                // add MODULE_NAME, MODULE_ID to every RouterConfig
                config_json.put("module_name", MODULE_NAME);
                config_json.put("module_id", MODULE_ID);

                //RouterConfig router_config = new RouterConfig(config_json);

                START_ROUTERS.add(config_json);
            }

        return true;
    } // end get_config()

    // *******************************************************************************************************************
    // *************************** Class Sensor  *************************************************************************
    // *******************************************************************************************************************
    // This class holds the sensor data
    // received in the 'params' property of the 'add_sensor' eventbus method message
    private class Sensor {
        public String sensor_id;
        public String sensor_type;
        public JsonObject info;
        // e.g. {
        //        "sensor_id": "0018b2000000113e",
        //        "sensor_type": "lorawan",
        //        "destination_id": "0018b2000000abcd",
        //        "destination_type": "everynet_jsonrpc"
        //      }

        // Constructor
        Sensor(JsonObject sensor_info) throws MsgRouterException
        {
            sensor_id = sensor_info.getString("sensor_id");
            sensor_type = sensor_info.getString("sensor_type");
            String destination_id = sensor_info.getString("destination_id");
            String destination_type = sensor_info.getString("destination_type");
            if (sensor_id == null || sensor_type == null || destination_id == null || destination_type == null)
            {
                throw new MsgRouterException("missing key on sensor create");
            }

            info = sensor_info;
            //logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
            //     ": added sensor "+this.toString());
        }

        public String toString()
        {
            return sensor_type+"/"+sensor_id + " -> " +
                info.getString("destination_type")+"/"+info.getString("destination_id");
        }
    }

    // *******************************************************************************************************************
    // *************************** Class Destination  ********************************************************************
    // *******************************************************************************************************************
    // This class holds the LoraWAN destination (i.e. http destination) data
    // received in the 'params' property of the 'add_destination' eventbus method message
    private class Destination {
        public String destination_type;  // Type of destination, e.g. "everynet_jsonrpc"
        public String destination_id;    // Id.  (destination_type,destination_id) is unique
        public JsonObject info;          // Data packet defining Destination as received from eventbus add_destination
                                         // or PostgreSQL csn_destination table

        public WebClient web_client;   // We pre-define an WebClient for each Destination. Hopefully this is more efficient.
        UrlParts u;                      // To hold the results of the parse_url()

        private class UrlParts {         // The results from using Java URL parsing in parse_url
            public boolean http_ssl;
            public int     http_port;
            public String  http_host;
            public String  http_path;
        }

        // { "destination_id": "xyz",
        //   "http_token":"foo!bar", // optional
        //   "http_token_header": "x-api-key" // optional - default to X-Auth-Token
        //   "url": "http://localhost:8080/efgh"
        // }

        // Constructor
        // Here is where we create a new Destination on receipt of a "add_destination" message or
        // loading rows from database table csn_destination
        //
        Destination(JsonObject destination_info) throws MsgRouterException
        {
            // destination_id is the definitive key
            // Will be used as lookup in "destinations" HashMap
            destination_id = destination_info.getString("destination_id");
            destination_type = destination_info.getString("destination_type");

            if (destination_id == null || destination_type == null)
            {
                throw new MsgRouterException("missing key on destination create");
            }

            try
            {
                // The user originally gave a URL, which could be malformed, if so this will throw an
                // exception
                u = parse_url(destination_info.getString("url"));
            }
            catch (MalformedURLException e)
            {
                throw new MsgRouterException("bad URL on destination create");
            }

            // Store entire Json payload into "info"
            info = destination_info;
            // inject http_path into the destination "info"
            info.put("http_path", u.http_path);

            WebClientOptions options = new WebClientOptions()
                                           .setSsl(u.http_ssl)
                                           .setTrustAll(true)
                                           .setDefaultPort(u.http_port)
                                           .setDefaultHost(u.http_host);

            web_client = WebClient.create(vertx, options);

            //logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
            //     ": created destination "+this.toString());
        }

        // Parse the string url into it's constituent parts for WebClientOptions
        private UrlParts parse_url(String url_string) throws MalformedURLException
        {
            URL url = new URL(url_string);

            UrlParts u = new UrlParts();

            u.http_ssl = url.getProtocol().equals("https");

            u.http_port = url.getPort();
            if (u.http_port < 0)
            {
                u.http_port = u.http_ssl ? 443 : 80;
            }

            u.http_host = url.getHost();

            u.http_path = url.getPath();

            return u;
        }

        public String toString()
        {
            String http_token = info.getString("http_token","");

            String http_token_header = info.getString("http_token_header","");

            UrlParts u = null;

            try
            {
                u = parse_url(info.getString("url"));
            }
            catch (MalformedURLException e)
            {
                return "bad URL";
            }

            return destination_type+"/"+destination_id+" -> "+
                   "<"+http_token_header+": "+http_token+"> "+
                   (u.http_ssl ? "https://" : "http://")+
                   u.http_host +":"+
                   u.http_port +
                   u.http_path;
        }

        // Here is where we POST the data to the destination
        public void send(String msg)
        {
            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                       ": sending to "+destination_type+"/"+destination_id+": " + msg);

            try
            {
                Buffer post_body = Buffer.buffer(msg);

                // Build request
                HttpRequest<Buffer> request = web_client.post(u.http_path);

                // Add optional token header
                // info is the original router config
                String auth_token = info.getString("http_token");
                if (auth_token != null)
                {
                    // auth_token_header in the config is optional, defaults to "X-Auth-Token"
                    String auth_token_header = info.getString("http_token_header", "X-Auth-Token");
                    request.putHeader(auth_token_header, auth_token);
                }

                // Add remaining settings and send POST
                request.putHeader("content-type", "application/json")
                    .timeout(15000) // give up after 15 seconds
                    // send this POST...
                    .sendBuffer( post_body, async_response -> {
                        if (async_response.succeeded())
                        {
                            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                       ": msg posted to " + this.toString());

                            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                       ": response was " + async_response.result().statusCode());
                        }
                        else // async_response failed
                        {
                            logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                                       ": Destination HttpClientRequest error for "+destination_id);

                            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                       ": POST FAILED " + async_response.cause().getMessage() );
                        }
                    }); // end .send

            }
            catch (Exception e)
            {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                           ": Destination send error for "+destination_type+"/"+destination_id);
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+": "+e.getMessage());
            }
        }

    } // end class Destination


    // *******************************************************************************************************************
    // *******************************************************************************************************************
    // ********************** Class Destinations  ************************************************************************
    // *******************************************************************************************************************
    // *******************************************************************************************************************
    // A collection of Destination objects, with get, put
    class Destinations
    {
        private HashMap<String,HashMap<String,Destination>> destinations; // stores destination_type->destination_id -> http POST mapping

        // Constructor, called in Verticle.start()
        public Destinations()
        {
            destinations = new HashMap<String,HashMap<String,Destination>>();
        }

        public int type_count()
        {
            return destinations.size();
        }

        // Get: access method, destination_type,destination_id-> Destination
        public Destination get(String destination_type, String destination_id) throws NullPointerException
        {
            HashMap<String,Destination> d_type;
            Destination d;
            String lcase_id = destination_id.toLowerCase();
            String lcase_type = destination_type.toLowerCase();
            try {
                 d_type = destinations.get(lcase_type);
            }
            catch (Exception NullPointerException) {
                logger.log(Constants.LOG_WARN, "Bad destination type: "+ destination_type);
                throw NullPointerException;
            }

            try {
                d = d_type.get(lcase_id);
            }
            catch (Exception NullPointerException) {
                logger.log(Constants.LOG_WARN, "Bad destination id: "+ destination_id);
                throw NullPointerException;
            }

            return d;
        }

        // Add a destination (destination_id, http_token, url) to destinations, having received an 'add_destination' manager message
        public boolean put(JsonObject destination_info)
        {
            Destination destination;
            try
            {
                // Create Destination object for this destination
                destination = new Destination(destination_info);

            }
            catch (MsgRouterException e)
            {
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                       ": add_destination failed with "+e.getMessage());
                return false;
            }

            // ***********************************************
            // add the destination to the current list (HashMap)
            // ***********************************************

            // If this destination is the first of its type, create a new HashMap for that type
            HashMap<String,Destination> type_destinations = destinations.get(destination.destination_type);

            if (type_destinations == null)
            {
                type_destinations = new HashMap<String, Destination>();
                // add new destinations hashmap to global destinations hashmap
                destinations.put(destination.destination_type.toLowerCase(), type_destinations);
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                           ": added new destination_type \""+destination.destination_type+"\" to destinations in-memory store");
            }

            // Now we can add this destination to the appropriate type_destinations HashMap in the destinations HashMap
            type_destinations.put(destination.destination_id.toLowerCase(), destination);

            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                       ": added destination "+destination.toString());
            return true;
        }

        public void remove(JsonObject destination_info)
        {
            String destination_id = destination_info.getString("destination_id");
            String destination_type = destination_info.getString("destination_type");

            if (destination_id == null || destination_type == null)
            {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                           ": skipping remove_destination manager message ('destination_id' or 'destination_type' property missing) on "+EB_MANAGER);
                return;
            }

            // Remove from the current list (HashMap) of objects - ignore if it is missing
            try
            {
                destinations.get(destination_type.toLowerCase()).remove(destination_id.toLowerCase());
            }
            catch (Exception NullPointerException)
            {;}

            //logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
            //           ": remove_destination count now "+destinations.size());
        }

        public void print()
        {
            for (HashMap<String,Destination> destination_type : destinations.values())
            {
                for (Destination destination : destination_type.values())
                {
                    logger.log(Constants.LOG_INFO, destination.toString());
                }
            }
        }

    } // end class Destinations


    // *******************************************************************************************************************
    // *******************************************************************************************************************
    // ********************* Class Sensors  ******************************************************************************
    // *******************************************************************************************************************
    // *******************************************************************************************************************
    private class Sensors
    {
        private HashMap<String,HashMap<String,Sensor>> sensors; // stores sensor_type-> sensor_id -> destination_type/id mapping

        Sensors()
        {
            sensors = new HashMap<String,HashMap<String,Sensor>>();
        }

        public int type_count()
        {
            return sensors.size();
        }

        public Sensor get(String sensor_type, String sensor_id) throws NullPointerException
        {
            return sensors.get(sensor_type.toLowerCase()).get(sensor_id.toLowerCase());
        }

        // Add a sensor to sensors, having received an 'add_sensor' manager message
        public void put(JsonObject sensor_info)
        {
            Sensor sensor;
            // Try creating a new Sensor from sensor_info
            try
            {
                // Create a Sensor object for this sensor
                sensor = new Sensor(sensor_info);
            }
            catch (MsgRouterException e)
            {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                           ": add_sensor failed with "+e.getMessage());
                return;
            }

            // ***********************************************
            // add the sensor to the current list (HashMap)
            // ***********************************************

            // If this sensor is the first of its type, create a new HashMap for that type
            HashMap<String,Sensor> type_sensors = sensors.get(sensor.sensor_type.toLowerCase());
            if (type_sensors == null)
            {
                type_sensors = new HashMap<String, Sensor>();
                // add new sensors hashmap to global sensors hashmap
                sensors.put(sensor.sensor_type.toLowerCase(), type_sensors);
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                           ": added new sensor_type \""+sensor.sensor_type+"\" to sensors in-memory store");
            }

            // Now we can add this sensor to the appropriate type_sensors HashMap in the sensors HashMap
            type_sensors.put(sensor.sensor_id.toLowerCase(), sensor);

            // logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
            //           ": sensor count now "+sensors.size());
        }

        // Remove a LoraWAN sensor from sensors, having received a 'remove_sensor' manager message
        public void remove(JsonObject sensor_info)
        {
            String sensor_id = sensor_info.getString("sensor_id");
            String sensor_type = sensor_info.getString("sensor_type");
            if (sensor_id == null || sensor_type == null)
            {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                           ": skipping remove_sensor manager message ('sensor_id' or 'sensor_type' property missing) on "+EB_MANAGER);
                return;
            }

            // remove the sensor from the current list (HashMap) - ignore if it is missing
            try
            {
                sensors.get(sensor_type.toLowerCase()).remove(sensor_id.toLowerCase());
            }
            catch (Exception NullPointerException)
            {;}

            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                       ": remove_sensor, count now "+sensors.size());
        }

        public void print()
        {
            for (HashMap<String, Sensor> sensor_type : sensors.values())
            {
                for (Sensor sensor : sensor_type.values())
                {
                    logger.log(Constants.LOG_INFO, sensor.toString());
                }
            }
        }

    } // end class Sensors

    // Exception thrown if MsgRouter fails to add a sensor or a destination
    class MsgRouterException extends Exception
    {
        public MsgRouterException()
        {
        }

        public MsgRouterException(String message)
        {
            super(message);
        }
    }
} // end class MsgRouter
