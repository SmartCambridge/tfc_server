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
import uk.ac.cam.tfc_server.util.Position;

public class RTMonitor extends AbstractVerticle {

    private final String VERSION = "0.08";
    
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

                response.end(home_page());
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
    //
    // start_monitor will create an eventbus listener to receive the required messages
    // and a websocket listener to wait for connections from clients.i
    private void start_monitor(JsonObject config, Router router)

    {
        final String ADDRESS = config.getString("address");

        final String URI = config.getString("http.uri", BASE_URI+"/"+ADDRESS);

        final String RECORDS_ARRAY = config.getString("records_array");

        final String RECORD_INDEX = config.getString("record_index");
        
        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                   ": setting up monitor for "+ADDRESS+" at "+HTTP_PORT+":"+URI);

        // create Monitor entry
        monitors.add(URI, ADDRESS, RECORDS_ARRAY, RECORD_INDEX);

        // and set up consumer for eventbus messages
        eb.consumer(ADDRESS, message -> {
                        handle_message(URI, message.body().toString());
                   
            });
                    
        // *********************************
        // create handler for browser socket 
        // *********************************

        SockJSHandlerOptions sock_options = new SockJSHandlerOptions().setHeartbeatInterval(2000);

        SockJSHandler sock_handler = SockJSHandler.create(vertx, sock_options);

        sock_handler.socketHandler( sock -> {
                // received new socket CONNECTION
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                        ": "+URI+" sock connection received with "+sock.writeHandlerID());

                // Assign a handler function to RECEIVE DATA
                sock.handler( buf -> {
                    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                           ": sock received '"+buf+"'");

                    JsonObject sock_msg;

                    // ignore incoming messages that fail to parse as Json
                    try
                    {
                        sock_msg = new JsonObject(buf.toString());
                    }
                    catch (io.vertx.core.json.DecodeException e)
                    {
                        logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                                ": socketHandler received non-Json message "+sock.writeHandlerID());
                        send_nok(sock,"","Message failed to parse as JsonObject");
                        return;
                    }

                    // Check the "msg_type" and process accordingly
                    // A basic ping check protocol
                    if (sock_msg.getString("msg_type","").equals(Constants.SOCKET_RT_PING))
                    {
                       // Send rt_pong in reply
                       sock.write(Buffer.buffer("{ \"msg_type\": \""+Constants.SOCKET_RT_PONG+"\" }"));
                    }
                    // The connecting page is expected to first send { "msg_type": "rt_connect" ... }
                    else if (sock_msg.getString("msg_type","").equals(Constants.SOCKET_RT_CONNECT))
                    {
                       // Add client with this connection to the client table
                       create_rt_client(URI, sock.writeHandlerID(), sock, sock_msg);
                       // Send rt_connect_ok in reply
                       sock.write(Buffer.buffer("{ \"msg_type\": \""+Constants.SOCKET_RT_CONNECT_OK+"\" }"));
                    }
                    // A "rt_subscribe" / "rt_unsubscribe" subscription requests from this client
                    else if (sock_msg.getString("msg_type","").equals(Constants.SOCKET_RT_SUBSCRIBE))
                    {
                       // Add this subscription to the client
                       create_rt_subscription(URI, sock.writeHandlerID(), sock_msg);
                    }
                    else if (sock_msg.getString("msg_type","").equals(Constants.SOCKET_RT_UNSUBSCRIBE))
                    {
                       // Remove this subscription from the client
                       remove_rt_subscription(URI, sock.writeHandlerID(), sock_msg);
                    }
                    else if (sock_msg.getString("msg_type","").equals(Constants.SOCKET_RT_REQUEST))
                    {
                       // Client has requested a 'pull' of the data
                       handle_rt_request(URI, sock.writeHandlerID(), sock_msg);
                    }
                });

                // Assign handler for socket CLOSED
                sock.endHandler( (Void v) -> {
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                ": sock closed "+sock.writeHandlerID());
                        // remove the client
                        remove_rt_client(URI, sock.writeHandlerID());
                    });
          });

        router.route(URI+"/*").handler(sock_handler);

        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                           ": socket handler setup on '"+URI+"/*"+"'");

    } // end start_monitor()

    // Send the client a "NOT OK" message
    private void send_nok(SockJSSocket sock, String request_id, String comment)
    {
        JsonObject sock_msg = new JsonObject();

        sock_msg.put("msg_type", Constants.SOCKET_RT_NOK);

        sock_msg.put("request_id", request_id);

        sock_msg.put("comment", comment);

        sock.write(Buffer.buffer(sock_msg.toString()));
    }

    // *****************************************************************************************
    // *****************************************************************************************
    // *************  Handle eventbus messages that a consumer has received ********************
    // *****************************************************************************************
    // *****************************************************************************************
    private void handle_message(String URI, String msg)
    {
        //logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+": eventbus message for "+URI);

        // Update the state of the relevant monitor, e.g. accumulate the latest and previous records
        monitors.update_state(URI, new JsonObject(msg));
        // Update the relevant clients that have subscribed
        monitors.update_clients(URI, new JsonObject(msg));
    }
    
    // *****************************************************************************************
    // *************  Handle a client connection     *******************************************
    // *****************************************************************************************
    private void create_rt_client(String URI, String UUID, SockJSSocket sock, JsonObject sock_msg)
    {
        // create entry in client table for correct monitor
        monitors.add_client(URI, UUID, sock, sock_msg);

        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                ": adding client "+UUID+" with "+sock_msg.toString());
    }

    // *****************************************************************************************
    // *************  Handle a subscription request  *******************************************
    // *****************************************************************************************
    private void create_rt_subscription(String URI, String UUID, JsonObject sock_msg)
    {
        // create entry in client table for correct monitor
        monitors.add_subscription(URI, UUID, sock_msg);

        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                ": subscribing client "+UUID+" with "+sock_msg.toString());
    }

    // *****************************************************************************************
    // *************  Remove a subscription          *******************************************
    // *****************************************************************************************
    private void remove_rt_subscription(String URI, String UUID, JsonObject sock_msg)
    {
        // create entry in client table for correct monitor
        monitors.remove_subscription(URI, UUID, sock_msg);

        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                ": removing subscription from "+UUID+" with "+sock_msg.toString());
    }

    // *****************************************************************************************
    // *************  Close a subscription request  ********************************************
    // *****************************************************************************************
    private void remove_rt_client(String URI, String UUID)
    {
        // remove entry in client table for correct monitor
        monitors.remove_client(URI, UUID);

        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                ": removed client "+UUID+" from monitor "+URI);
    }

    // *****************************************************************************************
    // *************  Client requests 'pull' of data *******************************************
    // *****************************************************************************************
    private void handle_rt_request(String URI, String UUID, JsonObject sock_msg)
    {
        monitors.handle_rt_request(URI, UUID, sock_msg);

        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                ": rt_request from client "+UUID+" for monitor "+URI);
    }

    // *****************************************************************************************
    // *****************************************************************************************
    // *************  The 'Monitor' data structures  *******************************************
    // *****************************************************************************************
    // *****************************************************************************************

    // A Monitor is instantiated for each feed for which real-time updated state is required.
    // The procedure 'update_state' is called each time a message arrives on the EventBus address.
    // On that asynchronous event the Monitor will update the internal state 
    // and send data as appropriate to the subscribing websockets.
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
        public String address;                  // EventBus address consumed
        public ArrayList<String> records_array;  // JsonArray property of data records e.g. "request_data"
        public ArrayList<String> record_index;   // 'primary key' Json property (within data records)
        public ClientTable clients;              // Set of sockets subscribing to this data

        public Hashtable<String, JsonObject> latest_records; // Holds latest message for each key 
        public Hashtable<String, JsonObject> previous_records; // Holds previous message for each key

        public JsonObject latest_msg; // Most recent message received on the eventbus
        public JsonObject previous_msg; // previous message received on the eventbus

        // Create a new Monitor, typically via MonitorTable.add(...)
        Monitor(String address, String records_array, String record_index) {
            this.address = address;
            // parse monitor config records_index e.g. "A>B>C" pointing to index field WITHIN record
            if (record_index == null)
            {
                this.record_index = new ArrayList<String>();
            }
            else
            {
                this.record_index = string_to_array(record_index);
            }
            // parse monitor config records_array e.g. "D>E>F" pointing to records array
            if (records_array == null)
            {
                this.records_array = new ArrayList<String>();
                logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+
                           ": created Monitor, single messages (index '"+
                           array_to_string(this.record_index)+") from "+address);
            }
            else
            {
                this.records_array = string_to_array(records_array);
                logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+
                           ": created Monitor, record array '"+array_to_string(this.records_array)+
                           "' (index '"+array_to_string(this.record_index)+"') from "+address);
            }
            latest_records = new Hashtable<String, JsonObject>();
            previous_records = new Hashtable<String, JsonObject>();

            clients = new ClientTable();
        }

        // A relevant message has appeared on the EventBus, so update this monitor state
        public void update_state(JsonObject eventbus_msg)
        {
            //logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+": update_state "+address);

            // in any case, store 'latest_msg' and 'previous_msg'
            if (latest_msg != null)
            {
                previous_msg = latest_msg;
            }

            latest_msg = eventbus_msg;

            // This monitor may be for single records (i.e. msg = record)
            // or multiple records may be contained within nested 'records_array' object
            if (records_array.size() == 0)
            {
                // The whole message is considered the 'record'
                update_record(eventbus_msg);
            }
            else
            {
                JsonArray records = get_records(eventbus_msg);
                for (int i=0; i<records.size(); i++)
                {
                    update_record(records.getJsonObject(i));
                }
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                           ": Monitor "+address+" processed "+records.size()+" records, total "+latest_records.size());
            }
        }        

        // update_state has picked out a data record from an incoming EventBus message,
        // so update the Monitor state based
        // on this record.
        private void update_record(JsonObject record)
        {
            String index_value = get_index(record);

            // logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
            //           ": update_record "+index_value);

            // if exists, shuffle latest_record to previous_record
            if (latest_records.get(index_value) != null)
            {
                previous_records.put(index_value, record);
            }
            // and store this new record into latest_record
            latest_records.put(index_value, record);
        }

        // update_state has updated the state, so now inform the websocket clients
        private void update_clients(JsonObject eventbus_msg)
        {
            // Note this monitor contains the updated 'state' so we pass this monitor to the clients 
            clients.update(eventbus_msg, this);
        }

        // Given an EventBus message, return the JsonArray containing the data records
        private JsonArray get_records(JsonObject msg)
        {
            // The message contains multiple records, so follow records_array path 
            // of JsonObjects and assume final element on path is JsonArray
            // containing data records of interest. Start with original message
            JsonObject records_parent = msg.copy();
            // step through the 'records_array' properties excluding the last
            for (int i=0; i<records_array.size()-1; i++)
            {
                records_parent = records_parent.getJsonObject(records_array.get(i));
            }
            // Now JsonObject records_parent contains the JsonArray with the
            // property as the last value in records_array.
            return records_parent.getJsonArray(records_array.get(records_array.size()-1));
        }

        // Given an EventBus message, return the string value of the record_index
        // i.e. for a SiriVM data record this will be the value of "VehicleRef"
        private String get_index(JsonObject record)
        {
            // The message contains multiple records, so follow records_array path 
            // of JsonObjects and assume final element on path is JsonArray
            // containing data records of interest. Start with original message
            JsonObject index_parent = record.copy();
            // step through the 'record_index' properties excluding the last
            for (int i=0; i<record_index.size()-1; i++)
            {
                index_parent = index_parent.getJsonObject(record_index.get(i));
            }
            // Now JsonObject records_parent contains the String with the
            // property as the last value in record_index.
            return index_parent.getString(record_index.get(record_index.size()-1));
        }

        // Add a client subscriber to this Monitor
        // The sock_msg contains a subscription e.g.
        // { "msg_type": "rt_connect",
        //   "filters": [
        //                { "key": "VehicleRef", "value": "SCCM-19612" }
        //              ]
        // }
        public String add_client(String UUID, SockJSSocket sock, JsonObject sock_msg)
        {
            // a simple add of the client
            return clients.add(UUID, sock, sock_msg);
        }

        // return true if the "key": "A>B>C" in the sock_msg matches the monitor 'record_index'
        public boolean test_record_index(JsonObject sock_msg)
        {
            // for an optimization, tell the client if the filter includes the 'record_index'
            // The first check is if the filter is for 'record_index'
            JsonArray filters = sock_msg.getJsonArray("filters");
            boolean key_is_record_index = false;
            if (record_index.size()>0 && filters != null)
            {
                for (int i=0; i<filters.size(); i++)
                {
                    JsonObject jo = filters.getJsonObject(i);
                    String key = jo.getString("key");
                    if (key_match(key, record_index))
                    {
                        key_is_record_index = true;
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                           ": subscription key "+key+" matches record_index");                        
                        break;
                    }
                }
            }
            return key_is_record_index;
        }

        public void add_subscription(String UUID, JsonObject sock_msg)
        {
            // for an optimization, tell the client if the filter includes the 'record_index'
            // The first check is if the filter is for 'record_index'
            // now we have key_is_record_index we can add the subscription to the client
            clients.add_subscription(UUID, sock_msg, test_record_index(sock_msg));
        }

        public void remove_subscription(String UUID, JsonObject sock_msg)
        {
            clients.remove_subscription(UUID, sock_msg);
        }

        // handle an incoming "rt_request" from client for one-off 'pull' of data
        public void handle_rt_request(String UUID, JsonObject sock_msg)
        {
            // note we pass *this* Monitor because it contains the state needed
            clients.handle_rt_request(UUID, sock_msg, this, test_record_index(sock_msg));
        }

        // Websocket from a client was closed so delete relevant client
        public void remove_client(String UUID)
        {
            clients.remove(UUID);
        }

        // Test whether a key (say "A>B>C") matches a string array ["D","E","F"]
        // This is used for an optimization where a key in a subscription matches
        // the 'primary key' of the records stored in this Monitor
        private boolean key_match(String key_string, ArrayList<String> key_array)
        {
            if (key_string == null)
            {
                return false;
            }
            return String.join(">",key_array).equals(key_string);
        }

        // Given string "A>B>C" return ArrayList["A","B","C"] 
        private ArrayList<String> string_to_array(String s)
        {
            return new ArrayList<String>(Arrays.asList(s.split(">")));
        }

        // return 'records_array' ["A","B","C"] as "A>B>C"
        private String array_to_string(ArrayList<String> array)
        {
            String str = "";
            for (int i=0; i<array.size(); i++)
            {
                if (i != 0)
                {
                    str += '>';
                }
                str += array.get(i);
            }
            return str;
        }

        // Return some human-readable description of this monitor as an HTML string
        public String toHtml()
        {
            String html = "<p><b>"+address+"</b>, records in <b>"+array_to_string(records_array)+
                    "</b>, record identifier in <b>"+array_to_string(record_index)+
                    "</b></p>";
            html += "<p>Clients: "+clients.size()+"</p>";
            html += clients.toHtml();
            return html;
        }

    } // end class Monitor
        
    // ****************************************************************************************
    // ****************************************************************************************
    // MonitorTable contains the data structure for each running Monitor
    // This has been implemented as a Class on the possibility that some broader-based access functions
    // may be needed (e.g. find a Monitor given a subscriber) but in the interim this Class could
    // equally just be the simple Hashtable defined within (i.e. the variable 'monitors')
    class MonitorTable {

        private Hashtable<String, Monitor> monitors;

        // Constructor to create a new MonitorTable.  This verticle only has one, in global var 'monitors'
        MonitorTable() {
            monitors = new Hashtable<String,Monitor>();
        }

        // The verticle supports multiple monitors, each is created via this 'add()' function.
        public void add(String uri, String address, String records_array, String record_index)
        {
            Monitor monitor = new Monitor(address, records_array, record_index);
            monitors.put(uri, monitor);
        }

        // add_client() is called when a client browser connects to a websocket
        public String add_client(String uri, String UUID, SockJSSocket sock, JsonObject sock_msg)
        {
            return monitors.get(uri).add_client(UUID, sock, sock_msg);
        }

        // add_subscription() is called when a websocket 'rt_subscribe" subscription arrives
        public void add_subscription(String uri, String UUID, JsonObject sock_msg)
        {
            monitors.get(uri).add_subscription(UUID, sock_msg);
        }

        // remove_subscription() is called when a websocket 'rt_unsubscribe' message arrives
        public void remove_subscription(String uri, String UUID, JsonObject sock_msg)
        {
            monitors.get(uri).remove_subscription(UUID, sock_msg);
        }

        // handle an incoming "rt_request" for client pull of data
        public void handle_rt_request(String uri, String UUID, JsonObject sock_msg)
        {
            monitors.get(uri).handle_rt_request(UUID, sock_msg);
        }

        // remove_client() is called when a websocket is closed
        public void remove_client(String uri, String UUID)
        {
            monitors.get(uri).remove_client(UUID);
        }

        public ClientTable get_clients(String uri)
        {
            return monitors.get(uri).clients;
        }

        // update_state() will be called when a new message arrives on the monitored eventbus address
        public void update_state(String uri, JsonObject msg)
        {
            monitors.get(uri).update_state(msg);
        }

        // update_clients() will be called after update_state() when new data arrives on the eventbus
        public void update_clients(String uri, JsonObject msg)
        {
            monitors.get(uri).update_clients(msg);
        }

        public Set<String> keySet()
        {
            return monitors.keySet();
        }

        public Monitor get(String key)
        {
            return monitors.get(key);
        }

        public int size()
        {
            return monitors.size();
        }
    } // end class MonitorTable
        
    // *******************************************************************************************
    // *******************************************************************************************
    // **********  The 'Client' data structures   ************************************************
    // *******************************************************************************************
    // *******************************************************************************************
    // Data for each socket connection
    // session_id is in sock.webSession().id()
    class Client {
        public String UUID;         // unique ID for this connection
        public SockJSSocket sock;   // actual socket reference
        public Hashtable<String,Subscription> subscriptions; // The actual "rt_subscribe" subscription 
                                                             // packet from web client

        // RTMonitor has received a 'rt_subscribe' message, so add to relevant client
        public void add_subscription(JsonObject sock_msg, boolean key_is_record_index)
        {            
            String request_id  = sock_msg.getString("request_id");
            if (request_id == null)
            {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                       ": missing request_id from "+UUID+" in "+sock_msg.toString());
                // No request_id, so send rt_nok message and return
                send_nok(sock, "no request_id given", "no request_id given in subscription");
                return;
            }

            Subscription s = new Subscription(sock_msg, request_id, key_is_record_index);

            subscriptions.put(request_id, s);

            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                       ": Client.add_subscription "+UUID+ " " +sock_msg.toString()+
                       " key_is_record_index="+s.key_is_record_index);
            return;
        }

        // RTMonitor has received a 'rt_unsubscribe' message
        public void remove_subscription(JsonObject sock_msg)
        {
            String request_id = sock_msg.getString("request_id");
            if (request_id==null)
            {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                    ": Client.remove_subscription() for "+UUID+" called with request_id==null");
                // No request_id, so send rt_nok message and return
                send_nok(sock, "no request_id given", "no request_id given in unsubscribe request");
                return;
            }
            Subscription s = subscriptions.remove(request_id);
            if (s==null)
            {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                    ": Client.remove_subscription() for "+UUID+" request_id '"+request_id+
                    "' not found");
                // send rt_nok message and return
                send_nok(sock, request_id, "request_id failed to match existing subscription");
                return;
            }
            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                    ": Client.remove_subscription() OK for "+UUID+" "+s.toString());
        }

        // Update this client based on incoming eventbus message
        public void update(JsonObject eventbus_msg, Monitor m)
        {
            // if this client has no subscriptions then skip
            if (subscriptions.size() == 0)
            {
                return;
            }
            // iterate the client subscriptions (so subscriptions are effectively OR
            for (String request_id: subscriptions.keySet())
            {
                Subscription s = subscriptions.get(request_id);
                Filters filters = s.filters;

                // Multiple filters in a single subscription are an AND

                // if there is NO definition of a 'records_array' in the config()
                // then the whole eventbus message is the data record and is sent (or not) unchanged.
                if (m.records_array.size() == 0)
                {
                    // The whole message is considered the 'record'
                    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                               ": Client.update apply filters to whole eventbus msg");

                    if (filters.test(eventbus_msg))
                    {
                        // filter succeeded, so send whole message
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                   ": Client.update filters succeeded, sending whole eventbus msg");
                        sock.write(Buffer.buffer(eventbus_msg.toString()));
                    }
                }
                // if the IS a records_array in the eventbus message, then iterate those records
                else
                {
                    // Extract the 'data records' from the eventbus message
                    JsonArray records = m.get_records(eventbus_msg);

                    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                               ": Client.update processing "+records.size()+" records");

                    // Prepare a JsonArray to hold the filtered records
                    JsonArray filtered_records = new JsonArray();

                    // iterate the eventbus records and accumulate filtered records
                    for (int record_num=0; record_num<records.size(); record_num++)
                    {
                        JsonObject record = records.getJsonObject(record_num);
                        if (filters.test(record))
                        {
                            filtered_records.add(record);
                        }
                    }

                    // If we have NO filtered records then return
                    if (filtered_records.size() == 0)
                    {
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                               ": Client.update 0 filtered records");
                        return;
                    }
                    // else we have a set of filtered records we can send as "rt_data" on client socket
                    else
                    {
                        // Woo we have successfully found records within the filter scope
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                               ": Client.update sending "+filtered_records.size()+" filtered records");

                        // Build the data object to be sent in response to this subscription
                        JsonObject rt_data = new JsonObject();
                        rt_data.put("msg_type", Constants.SOCKET_RT_DATA);
                        rt_data.put("request_data", filtered_records);
                        rt_data.put("request_id", request_id);
                        sock.write(Buffer.buffer(rt_data.toString()));
                    }

                }
            }
        }

        // Handle an incoming "rt_request" for one-off pull of data
        public void handle_rt_request(JsonObject sock_msg, Monitor m, boolean key_is_record_index)
        {            
            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                       ": Client.handle_rt_request "+UUID+ " " +sock_msg.toString()+
                       " key_is_record_index="+key_is_record_index);

            String request_id  = sock_msg.getString("request_id");
            if (request_id == null)
            {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                       ": Client.handle_rt_request missing request_id from "+UUID+" in "+sock_msg.toString());
                // No request_id, so send rt_nok message and return
                send_nok(sock, "no request_id given", "no request_id given in request");
                return;
            }

            JsonArray options;

            Filters filters;

            // ignore incoming messages that fail to parse as Json
            try
            {
                JsonArray filters_property = sock_msg.getJsonArray("filters", new JsonArray());
                filters = new Filters(filters_property);
            }
            catch (ClassCastException e)
            {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                        ": Client.handle_rt_request received non-JsonArray \"filters\" "+UUID);
                // Bad "filters" JsonArray, so send rt_nok message and return
                send_nok(sock, request_id, "request 'filters' property not valid JsonArray");
                return;
            }

            // ignore incoming messages that fail to parse as Json
            try
            {
                options = sock_msg.getJsonArray("options", new JsonArray());
            }
            catch (ClassCastException e)
            {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                        ": Client.handle_rt_request received non-JsonArray \"options\" "+UUID);
                // Bad "filters" JsonArray, so send rt_nok message and return
                send_nok(sock, request_id, "request 'options' property not valid JsonArray");
                return;
            }


            // The response to "rt_request" may be multiple messages
            // so create a JsonArray to accumulate them
            JsonArray reply_messages = new JsonArray();

            // Requests for "latest_msg" and "previous_msg" are not filtered
            if (options.contains("previous_msg"))
            {
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                        ": Client.handle_rt_request for previous_msg");

                reply_messages.add(m.previous_msg);
            }
           
            // "latest_msg" is the default if no "options" specified
            if (options.size() == 0 || options.contains("latest_msg"))
            {
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                        ": Client.handle_rt_request for latest_msg");

                reply_messages.add(m.latest_msg);
            }

            if (options.contains("previous_records"))
            {
                // Build reply JsonObject { "msg_type": "rt_data",
                //                          "request_id": request_id,
                //                          "options" : [ "previous_records" ],
                //                          "request_data": [ ... filtered records ... ]
                //                        }
                
                JsonObject msg_previous_records = new JsonObject();

                msg_previous_records.put("msg_type", Constants.SOCKET_RT_DATA);

                msg_previous_records.put("request_id", request_id);

                msg_previous_records.put("options", new JsonArray("[ \"previous_records\" ]"));

                // Build the recordset to send filtered 'previous records' as "request_data"
                // and add that recordset to the reply message:

                JsonArray filtered_previous_records = get_filtered_records(filters, m.previous_records);

                msg_previous_records.put("request_data", filtered_previous_records);

                // Add the reply message to the list of messages to be sent
                reply_messages.add(msg_previous_records);

                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                        ": Client.handle_rt_request for "+filtered_previous_records.size()+" previous_records");

            }

            if (options.contains("latest_records"))
            {
                // Build reply JsonObject { "msg_type": "rt_data",
                //                          "request_id": request_id,
                //                          "options" : [ "latest_records" ],
                //                          "request_data": [ ... filtered records ... ]
                //                        }
                
                JsonObject msg_latest_records = new JsonObject();

                msg_latest_records.put("msg_type", Constants.SOCKET_RT_DATA);

                msg_latest_records.put("request_id", request_id);

                msg_latest_records.put("options", new JsonArray("[ \"latest_records\" ]"));

                // Build the recordset to send filtered 'latest records' as "request_data"
                // and add that recordset to the reply message:

                JsonArray filtered_latest_records = get_filtered_records(filters, m.latest_records);

                msg_latest_records.put("request_data", filtered_latest_records);

                // Add the reply message to the list of messages to be sent
                reply_messages.add(msg_latest_records);

                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                        ": Client.handle_rt_request for "+filtered_latest_records.size()+" latest_records");

            }
           
           
            // Now send accumulated messages
            for (int i=0; i<reply_messages.size(); i++)
            {
                sock.write(Buffer.buffer(reply_messages.getJsonObject(i).toString()));
            }

            return;
        }

        // return a JsonArray from a filtered records Hashtable (e.g. monitor latest_records)
        private JsonArray get_filtered_records(Filters filters, Hashtable<String, JsonObject> records)
        {
            JsonArray filtered_records = new JsonArray();

            for (String key: records.keySet())
            {
                JsonObject record = records.get(key);
                if (filters.test(record))
                {
                    filtered_records.add(record);
                }
            }

            return filtered_records;
        }

        public String toHtml()
        {
            String html = UUID+"<br/>";
            for (String request_id: subscriptions.keySet())
            {
                html += subscriptions.get(request_id).toHtml()+"<br/>";
            }
            return html;
        }

    } // end class Client

    // Client subscription filter e.g. { "test": "=", "key": "A>B", "value": "X" }
    class Filter {
        public JsonObject msg;

        Filter(JsonObject msg)
        {
            this.msg = msg;
        }

        // Test a JsonObject records against this Filter
        public boolean test(JsonObject record)
        {
            // test can default to "="
            String test = msg.getString("test");
            if (test == null)
            {
                test = "=";
            }

            switch (test)
            {
                case "=": 
                    return test_equals(record);

                case "inside":
                    return test_inside(record);

                default:
                    logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                        ": Filter.test '"+test+"' not recognised");
                    break;
            }
            return false;
        } // end Filter.test()

        private boolean test_equals(JsonObject record)
        {
            // Given a filter say { "test": "=", "key": "VehicleRef", "value": "SCNH-35224" }
            String key = msg.getString("key");
            if (key == null)
            {
                return false;
            }

            //DEBUG TODO allow numeric value
            String value = msg.getString("value");
            if (value == null)
            {
                return false;
            }

            // Try and pick out the property "key" from the data record
            String record_value = record.getString(key);
            if (record_value == null)
            {
                return false;
            }

            //DEBUG TODO allow different tests than just "="
            return record_value.equals(value);
        } // end Filter.test_equals()

        private boolean test_inside(JsonObject record)
        {
            //DEBUG TODO implement this
            // Example filter:
            //   { "test": "inside",
            //     "lat_key": "Latitude",
            //     "lng_key": "Longitude",
            //     "points": [
            //         {  "lat": 52.21411510, "lng": 0.09916394948 },
            //         {  "lat": 52.20885583, "lng": 0.14877408742 },
            //         {  "lat": 52.19170630, "lng": 0.13778775930 },
            //         {  "lat": 52.19496839, "lng": 0.10053724050 }
            //     ]
            //   }

            //DEBUG TODO move this into the Subscription constructor for speedup
            String lat_key = msg.getString("lat_key", "acp_lat");

            String lng_key = msg.getString("lng_key", "acp_lng");

            JsonArray points = msg.getJsonArray("points");

            ArrayList<Position> polygon = new ArrayList<Position>();

            for (int i=0; i<points.size(); i++)
            {
                polygon.add(new Position(points.getJsonObject(i)));
            }

            double lat;
            double lng;
            
            try
            {
                lat = get_double(record, lat_key);

                lng = get_double(record, lng_key);
            }
            catch (Exception e)
            {
                return false;
            }

            Position pos = new Position(lat,lng);

            // ah, all ready, now we can call the 'inside' test of the Position.
            return pos.inside(polygon);

        } // end Filter.test_inside()

        // Get a 'double' from the data record property 'key'
        // e.g. return the value of a "Latitude" property.
        // Note this could be a string or a number...
        private double get_double(JsonObject record, String key)
        {
            try
            {
                return record.getDouble(key);
            }
            catch (ClassCastException e)
            {
                return Double.parseDouble(record.getString(key));
            }
            //throw new Exception("get_double failed to parse number");
        }

    } // end class Filter

    // List of filters in a given subscription
    class Filters {
        private ArrayList<Filter> filters;

        public JsonArray msg;

        // Construct new Filters object from "filters" JsonArray in client subscription
        Filters(JsonArray filters)
        {
            msg = filters;

            this.filters = new ArrayList<Filter>();

            if (filters != null)
            {
                // initialize the filters ArrayList with JsonObject filters from msg 
                for (int filter_num=0; filter_num<filters.size(); filter_num++)
                {
                    this.filters.add(new Filter(filters.getJsonObject(filter_num)));
                }
            }
        }

        // Test (AND) filters against a JsonObject data record
        public boolean test(JsonObject record)
        {
            
            // An empty filters array always succeeds
            if (filters.size() == 0)
            {
                return true;
            }

            // start with 'filter_passed = true' and set to false if any filter fails
            boolean filters_passed = true;

            // test all the filters on the eventbus_msg and send on websocket if it passes
            for (int filter_num=0; filter_num<filters.size(); filter_num++)
            {
                Filter filter = filters.get(filter_num);

                if (!filter.test(record))
                {
                    filters_passed = false;
                }
            }

            //logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
            //         ": tested filter"+filters.toString()+(filters_passed?"passed":"failed"));
            return filters_passed;
        } // end Filters.test()


    } // end class Filters

    // Subscription to the eventbus message data
    // Each eventbus message may contain a JsonArray of multiple data records
    // Each client can have multiple subscriptions
    class Subscription {
        public String request_id;
        public boolean key_is_record_index; // optimization flag if subscription is filtering on 'primary key'
        public JsonObject msg; // 'rt_subscribe' websocket message when subscription was requested
        public Filters filters;

        // Construct a new Subscription
        Subscription(JsonObject msg, String request_id, boolean key_is_record_index)
        {
            this.msg = msg;

            this.request_id = request_id;

            this.key_is_record_index = key_is_record_index;

            this.msg = msg;

            try
            {
                JsonArray filters_property = msg.getJsonArray("filters", new JsonArray());
                filters = new Filters(filters_property);
            }
            catch (ClassCastException e)
            {
                filters = new Filters(new JsonArray());
            }
        }

        public String toString()
        {
            return msg.toString();
        }

        public String toHtml()
        {
            return msg.toString();
        }
    } // end class Subscription

    // ***************************************************************
    // ***************************************************************
    // Object to store data for all current socket connections
    class ClientTable {

        private Hashtable<String,Client> client_table;

        // initialize new SockInfo object
        ClientTable () {
            client_table = new Hashtable<String,Client>();
        }

        // Add new connection to known list
        // 'UUID' is a unique key generated for this call
        // 'sock' is the socket the client connected on
        // 'sock_msg' is the JsonObject subscription message
        // returns UUID of entry added
        public String add(String UUID, SockJSSocket sock, JsonObject sock_msg)
        {
            if (sock == null)
            {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                    ": ClientTable.add() called with sock==null");
                return null;
            }

            // create new entry for sock_data
            Client client = new Client();

            client.UUID = UUID;

            client.sock = sock;

            // Create initially empty subscription list (will be indexed on "request_id")
            client.subscriptions = new Hashtable<String,Subscription>();

            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                       ": ClientTable.add "+UUID);
            // push this entry onto the array
            client_table.put(UUID, client);
            return UUID;
        }

        // add_subscription() - add incoming subscription to appropriate client
        // 'key_is_record_index' is an optimization, 'true' if this subscription constains
        //    "filters": [ ... { "key": "A>B>C" } ...], where "A>B>C" matches the record_index of this Monitor
        public void add_subscription(String UUID, JsonObject sock_msg, boolean key_is_record_index)
        {
            Client client = client_table.get(UUID);

            if (client == null)
            {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                    ": ClientTable.add_subscription() "+UUID+" called with client==null");
                return;
            }
            client.add_subscription(sock_msg, key_is_record_index);
        }

        // remove_subscription - remove from appropriate client
        public void remove_subscription(String UUID, JsonObject sock_msg)
        {
            Client client = client_table.get(UUID);

            if (client == null)
            {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                    ": ClientTable.remove_subscription() "+UUID+" called with client==null");
                return;
            }
            client.remove_subscription(sock_msg);
        }


        // handle an incoming client "rt_request"
        public void handle_rt_request(String UUID, JsonObject sock_msg, Monitor m, boolean key_is_record_index)
        {
            Client client = client_table.get(UUID);

            if (client == null)
            {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                    ": ClientTable.handle_rt_request() "+UUID+" called with client==null");
                return;
            }
            client.handle_rt_request(sock_msg, m, key_is_record_index);
        }
        
        public Client get(String UUID)
        {
            // retrieve data for current socket, if it exists
            Client client = client_table.get(UUID);

            if (client != null)
                {
                    return client;
                }
            logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                    ": ClientTable.get '"+UUID+"' entry not found in client_table");
            return null;
        }

        public void remove(String UUID)
        {
            Client client = client_table.remove(UUID);
            if (client == null)
            {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                    ": ClientTable.remove non-existent client "+UUID);
            }
        }

        // An eventbus message has come in..., update all the clients
        public void update(JsonObject eventbus_msg, Monitor m)
        {
            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                       ": "+m.address+" updating "+ client_table.size()+" clients");

            // for each client socket entry in sock_info
            //   if sock_data is not null
            //      if client.filters is null
            //      then send entire message to client
            //      elif msg or records matches filters
            //      then send relevant records to client

            // iterate the clients
            for (String UUID: client_table.keySet())
            {
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                       ": updating client "+UUID);

                Client client = client_table.get(UUID);
                
                if (client != null)
                {
                    client.update(eventbus_msg, m);
                }
            }
        }



        public Set<String> keySet()
        {
            return client_table.keySet();
        }

        public int size()
        {
            return client_table.size();
        }

        public String toHtml()
        {
            String html = "<p>";
            for (String UUID: client_table.keySet())
            {
                html += client_table.get(UUID).toHtml();
            }
            html += "</p>";
            return html;
        }

    } // end class ClientTable
   
    // String content of this verticle 'home' page
    private String home_page()
    {
        String page = "<html><head><title>RTMonitor v"+VERSION+"</title>";
        page +=    "<style> body { font-family: sans-serif;}</style></head><body>";
        page +=    "<h1>Adaptive City Platform</h1>";
        page +=    "<p>RTMonitor version "+VERSION+": "+MODULE_NAME+"."+MODULE_ID+"</p>";
        page +=    "<p>BASE_URI="+BASE_URI+"</p>";
        page +=     "<p>This RTMonitor has "+monitors.size()+" monitor(s):</p>"; 

        // iterate the monitors
        Set<String> keys = monitors.keySet();
        for (String key: keys)
        {
            page += monitors.get(key).toHtml();
        }
        page += "</body></html>";
        return page;
    }

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
