package uk.ac.cam.tfc_server.rtmonitor;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// RTMonitor.java
//
// RTMonitor supports the subscription to eventbus messages via a URL which returns the
// real-time data representing that description on a websocket
//
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the Adaptive City Platform
//
// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.DeploymentOptions;

import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpMethod;

import io.vertx.core.file.FileSystem;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.buffer.Buffer;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandlerOptions;
import io.vertx.ext.web.handler.sockjs.SockJSSocket;

import java.io.*;
import java.time.*;
import java.time.format.*;
import java.util.*;

// other tfc_server classes
import uk.ac.cam.tfc_server.util.Constants;
import uk.ac.cam.tfc_server.util.Log;

public class RTMonitor extends AbstractVerticle {

    private final String VERSION = "0.06";
    
    // from config()
    public int LOG_LEVEL;             // optional in config(), defaults to Constants.LOG_INFO
    private String MODULE_NAME;       // config module.name - normally "msgrouter"
    private String MODULE_ID;         // config module.id - unique for this verticle
    private String EB_SYSTEM_STATUS;  // config eb.system_status
    private String EB_MANAGER;        // config eb.manager

    private int HTTP_PORT;            // config rtmonitor.http.port
    
    private String BASE_URI; // used as template parameter for web pages, built from config()
    
    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 25;
    private final int SYSTEM_STATUS_RED_SECONDS = 35;

    private EventBus eb = null;
    private Log logger;

    // monitor configs:
    private JsonArray START_MONITORS; // config module_name.monitors parameters
    
    // data structure to hold eventbus and subscriber data for each monitor
    private MonitorTable monitors;
    
    @Override
    public void start(Future<Void> fut) throws Exception
    {
        // load initialization values from config()
        if (!get_config())
            {
                Log.log_err("RTMonitor."+ MODULE_ID + ": failed to load initial config()");
                vertx.close();
                return;
            }

        logger = new Log(LOG_LEVEL);
    
        logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": Version "+VERSION+
                   " started on port "+HTTP_PORT+" (log_level="+LOG_LEVEL+")");

        eb = vertx.eventBus();

        // send periodic "system_status" messages
        init_system_status();
        
        // initialize object to hold MonitorInfo for each monitor
        monitors = new MonitorTable();
    
        // *************************************************************************************
        // *************************************************************************************
        // *********** Start web server (incl Socket)
        // *************************************************************************************
        // *************************************************************************************
        HttpServer http_server = vertx.createHttpServer();

        Router router = Router.router(vertx);

        // ********************************
        // create handler for embedded page
        // ********************************

        router.route(BASE_URI+"/home").handler( routingContext -> {

                HttpServerResponse response = routingContext.response();
                response.putHeader("content-type", "text/html");

                response.end("<h1>Adaptive City Platform</h1><p>RTMonitor!</p>");
            });
        logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": serving homepage at "+BASE_URI+"/home");

        // iterate through all the monitors to be started
        for (int i=0; i<START_MONITORS.size(); i++)
        {
            start_monitor(START_MONITORS.getJsonObject(i), router);
        }

        http_server.requestHandler(router::accept).listen(HTTP_PORT);

    } // end start()

    // ***************************************************************************************
    // Set periodic timer to broadcast "system UP" status messages to EB_SYSTEM_STATUS address
    private void init_system_status()
    {
        vertx.setPeriodic(SYSTEM_STATUS_PERIOD, id -> {
                eb.publish(EB_SYSTEM_STATUS,
                           "{ \"module_name\": \""+MODULE_NAME+"\"," +
                           "\"module_id\": \""+MODULE_ID+"\"," +
                           "\"status\": \"UP\"," +
                           "\"status_amber_seconds\": "+String.valueOf( SYSTEM_STATUS_AMBER_SECONDS ) + "," +
                           "\"status_red_seconds\": "+String.valueOf( SYSTEM_STATUS_RED_SECONDS ) +
                           "}" );
            });
    }

    // ************************************************
    // ************************************************
    // Here is where the eventbus monitor is created
    // ************************************************
    // ************************************************
    private void start_monitor(JsonObject config, Router router)

    {
        final String ADDRESS = config.getString("address");

        final String URI = config.getString("http.uri", BASE_URI+"/"+ADDRESS);

        final String RECORDS_ARRAY = config.getString("records_array");

        final String RECORD_INDEX = config.getString("record_index");
        
        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                   ": setting up monitor for "+ADDRESS+" at "+HTTP_PORT+":"+URI);

        monitors.add(URI, ADDRESS, RECORDS_ARRAY, RECORD_INDEX);

        eb.consumer(ADDRESS, message -> {
                        handle_message(URI, message.body().toString());
                   
            });
                    
        //        send_client(sock, message.body().toString());
        //        });
        
        // *********************************
        // create handler for browser socket 
        // *********************************

        SockJSHandlerOptions sock_options = new SockJSHandlerOptions().setHeartbeatInterval(2000);

        SockJSHandler sock_handler = SockJSHandler.create(vertx, sock_options);

        sock_handler.socketHandler( sock -> {
                // Rita received new socket connection
                logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": "+URI+" sock connection received with "+sock.writeHandlerID());

                // Assign a handler funtion to receive data if send
                sock.handler( buf -> {
                   logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": sock received '"+buf+"'");

                   JsonObject sock_msg = new JsonObject(buf.toString());

                   if (sock_msg.getString("msg_type").equals(Constants.SOCKET_RT_CONNECT))
                   {
                       // Add this connection to the client table
                       // and set up consumer for eventbus messages
                       create_rt_subscription(URI, sock, sock_msg);
                   }
                });

                sock.endHandler( (Void v) -> {
                        logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": sock closed "+sock.writeHandlerID());
                    });
          });

        router.route(URI).handler(sock_handler);

    } // end start_monitor()

    // ***************************************************************************************************
    // ***************************************************************************************************
    // ******************  Handle eventbus messages that a consumer has received *************************
    // ***************************************************************************************************
    // ***************************************************************************************************
    private void handle_message(String uri, String msg)
    {
        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+": eventbus message for "+uri);
        monitors.update_state(uri, new JsonObject(msg));
    }
    
    // ***************************************************************************************************
    // ***************************************************************************************************
    // ******************  Handle a subscription request  ************************************************
    // ***************************************************************************************************
    // ***************************************************************************************************
    private void create_rt_subscription(String URI, SockJSSocket sock, JsonObject sock_msg)
    {
        // create entry in client table for correct monitor
        String UUID = monitors.add_client(URI, sock, sock_msg);

        //DEBUG TODO subscribe this socket to desired updates (i.e. add this socket as a client)
        
        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+": subscribing client with "+sock_msg.toString());
    }

    private void send_client(SockJSSocket sock, String msg)
    {
        sock.write(Buffer.buffer(msg));
    }

    //DEBUG TODO get this to send the right messages to the right subscriber
    // if the zone matches the zone_id in a user subscription
    // then forward the message on that socket
    private void send_user_messages(String uri, String msg)
    {
        //debug we're using a single hardcoded socket ref
        //debug will need to iterate through sockets in sock_info
        //debug not yet handling socket close

        JsonObject msg_jo = new JsonObject(msg);
        String msg_zone_id= msg_jo.getString("module_id");

        // for each client socket entry in sock_info
        //   if sock_data is not null
        //     for each zone_id in subscription on that socket
        //       if zone_id == zone_id in Zone msg
        //         then forward the message on this socket

        ClientTable client_table = monitors.get_clients(uri);
        
        for (String UUID: client_table.keys())
            {
                ClientInfo client_info = client_table.get(UUID);
                
                if (client_info != null)
                    {
                        //DEBUG TODO get the zone crap out of here and filter messages properly
                        for (String zone_id: client_info.zone_ids)
                            {
                                if (zone_id.equals(msg_zone_id))
                                    {
                                        client_info.sock.write(Buffer.buffer(msg));
                                    }
                            }
                    }
            }
    }
    
    // generate a new Unique User ID for each socket connection
    private String get_UUID()
    {
        return String.valueOf(System.currentTimeMillis());
    }


    // ***************************************************************************************************
    // ***************************************************************************************************
    // ******************  The 'Monitor' data structures  ************************************************
    // ***************************************************************************************************
    // ***************************************************************************************************

    // A Monitor is instantiated for each feed for which real-time updated state is required.
    // The procedure 'update_state' is called each time a message arrives on the EventBus address assigned.
    // On that asynchronous event the Monitor will update the internal state and send data as appropriate to
    // the subscribing websockets.
    //
    // Incoming EventBus messages may be considered single 'records', i.e. the entire message is the data
    // recorf of interest, or each EventBus message may contain a JsonArray of multiple data 'records'.
    // In the latter case the monitor config() will give the 'records_array' property with the path to the
    // JsonArray in the message.
    //
    // The Monitor will typically be told the 'key' field for the incoming EventBus
    // messages and that will be used to index the state. E.g. for SiriVM bus position data this is
    // likely to be 'MonitoredVehicleRef', such that the Monitor will maintain the 'latest' position for
    // each vehicle.
    class Monitor {
        public String address;                   // EventBus address consumed
        public ArrayList<String> records_array;  // Json property location of the required data e.g. "request_data"
        public String record_index;              // Json property (within records_array fields) containing sensor id
        public ClientTable clients;              // Set of sockets subscribing to this data

        private Hashtable<String, JsonObject> latest_record; // Holds data from the latest EventBus message 
        private Hashtable<String, JsonObject> previous_record; // Holds data from the previous EventBus message 

        // Create a new Monitor, typically via MonitorTable.add(...)
        Monitor(String address, String records_array, String record_index) {
            this.address = address;
            if (records_array == null)
            {
                this.records_array = new ArrayList<String>();
                logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": created Monitor for flat single messages from "+address);
            }
            else
            {
                this.records_array = new ArrayList<String>(Arrays.asList(records_array.split(">")));
                logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+
                           ": created Monitor for record array in '"+records_array_string()+"' from "+address);
            }
            clients = new ClientTable();
        }

        // A relevant message has appeared on the EventBus, so update this monitor state
        public void update_state(JsonObject msg)
        {
            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+": update_state "+address);
            // This monitor may be for single records (i.e. msg = record)
            // or multiple records may be contained within nested 'records_array' object
            if (records_array.size() == 0)
            {
                // The whole message is considered the 'record'
                update_record(msg);
                update_clients(msg);
            }
            else
            {
                JsonArray records = get_records(msg);
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+": update_state processing "+records.size()+" records");
            }
        }        

        // update_state has picked out a data record from an incoming EventBus message, so update the Monitor state based
        // on this record.
        private void update_record(JsonObject record)
        {
            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+": update_record");
        }

        // update_state has updated the state, so now inform the websocket clients
        private void update_clients(JsonObject msg)
        {
            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+": updating clients");
        }

        // Given an EventBus message, return the JsonArray containing the data records
        private JsonArray get_records(JsonObject msg)
        {
            // The message contains multiple records, so follow records_array path of JsonObjects
            // and assume final element on path is JsonArray containing data records of interest.
            // Start with original message
            JsonObject records_parent = msg.copy();
            // step through the 'records_array' properties excluding the last
            for (int i=0; i<records_array.size()-1; i++)
            {
                records_parent = records_parent.getJsonObject(records_array.get(i));
            }
            // Now JsonObject records_parent contains the JsonArray with the property as the last value in records_array.
            return records_parent.getJsonArray(records_array.get(records_array.size()-1));
        }

        // return 'records_array' ["A","B","C"] as "A>B>C"
        private String records_array_string()
        {
            String str = "";
            for (int i=0; i<records_array.size(); i++)
            {
                if (i!=0)
                {
                    str += '>';
                }
                str += records_array.get(i);
            }
            return str;
        }

    } // end class Monitor
        
    // MonitorTable contains the data structure for each running Monitor
    // This has been implemented as a Class on the possibility that some broader-based access functions
    // may be needed (e.g. find a Monitor given a subscriber) but in the interim this Class could
    // equally just be the simple Hashtable defined within (i.e. the variable 'monitors')
    class MonitorTable {

        private Hashtable<String, Monitor> monitors;

        MonitorTable() {
            monitors = new Hashtable<String,Monitor>();
        }

        public void add(String uri, String address, String records_array, String record_index)
        {
            Monitor monitor = new Monitor(address, records_array, record_index);
            monitors.put(uri, monitor);
        }

        public String add_client(String uri, SockJSSocket sock, JsonObject sock_msg)
        {
            return monitors.get(uri).clients.add(sock, sock_msg);
        }

        public ClientTable get_clients(String uri)
        {
            return monitors.get(uri).clients;
        }

        public void update_state(String uri, JsonObject msg)
        {
            monitors.get(uri).update_state(msg);
        }

    } // end class MonitorTable
        
    // ***************************************************************************************************
    // ***************************************************************************************************
    // ******************  The 'Client' data structures   ************************************************
    // ***************************************************************************************************
    // ***************************************************************************************************
    // Data for each socket connection
    // session_id is in sock.webSession().id()
    class ClientInfo {
        public String UUID;         // unique ID for this connection
        public SockJSSocket sock;   // actual socket reference
        public ArrayList<String> zone_ids; // zone_ids relevant to this client connection
    }

    // Object to store data for all current socket connections
    class ClientTable {

        private Hashtable<String,ClientInfo> client_table;

        // initialize new SockInfo object
        ClientTable () {
            client_table = new Hashtable<String,ClientInfo>();
        }

        // Add new connection to known list, with zone_ids in buf
        // returns UUID of entry added
        public String add(SockJSSocket sock, JsonObject sock_msg)
        {
            if (sock == null)
                {
                    System.err.println("Rita."+MODULE_ID+": ClientTable.add() called with sock==null");
                    return null;
                }

            // create new entry for sock_data
            ClientInfo entry = new ClientInfo();
            entry.sock = sock;

            entry.zone_ids = new ArrayList<String>();

            JsonArray zones_ja = sock_msg.getJsonArray("zone_ids");
            for (int i=0; i<zones_ja.size(); i++)
                {
                    entry.zone_ids.add(zones_ja.getString(i));
                }
            logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+
                       ": ClientTable.add "+sock_msg.getString("UUID")+ " " +entry.zone_ids.toString());
            // push this entry onto the array
            String UUID = sock_msg.getString("UUID");        
            client_table.put(UUID,entry);
            return UUID;
        }

        public ClientInfo get(String UUID)
        {
            // retrieve data for current socket, if it exists
            ClientInfo client_config = client_table.get(UUID);

            if (client_config != null)
                {
                    return client_config;
                }
            System.err.println("Rita."+MODULE_ID+": ClientTable.get '"+UUID+"' entry not found in client_table");
            return null;
        }

        public void remove(String UUID)
        {
            ClientInfo client_config = client_table.remove(UUID);
            if (client_config == null)
                {
                    System.err.println("Rita."+MODULE_ID+": ClientTable.remove non-existent session_id "+UUID);
                }
        }

        public Set<String> keys()
        {
            return client_table.keySet();
        }
    } // end class ClientTable
    
    // Load initialization global constants defining this module from config()
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
          Log.log_err("RTMonitor: module.name config() not set");
          return false;
        }
        
        MODULE_ID = config().getString("module.id");
        if (MODULE_ID == null)
        {
            Log.log_err(MODULE_NAME+": module.id config() not set");
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
          Log.log_err(MODULE_NAME+"."+MODULE_ID+": eb.system_status config() not set");
          return false;
        }

        EB_MANAGER = config().getString("eb.manager");
        if (EB_MANAGER == null)
        {
          Log.log_err(MODULE_NAME+"."+MODULE_ID+": eb.manager config() not set");
          return false;
        }

        // port for user browser access to this Rita
        HTTP_PORT = config().getInteger(MODULE_NAME+".http.port",0);
        if (HTTP_PORT==0)
        {
            System.err.println(MODULE_NAME+"."+MODULE_ID+": "+MODULE_NAME+".http.port not in config()");
            return false;
        }

        BASE_URI = config().getString(MODULE_NAME+".http.uri","/"+MODULE_NAME+"/"+MODULE_ID);
        
        START_MONITORS = config().getJsonArray(MODULE_NAME+".monitors");
        
        return true;
    }

} // end class RTMonitor
