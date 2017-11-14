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

        monitors.add(URI, ADDRESS, RECORDS_ARRAY, RECORD_INDEX);

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

                   JsonObject sock_msg = new JsonObject(buf.toString());

                   // The connecting page is expected to have { "msg_type": "rt_connect" ... }
                   if (sock_msg.getString("msg_type","oh crap").equals(Constants.SOCKET_RT_CONNECT))
                   {
                       // Add this connection to the client table
                       // and set up consumer for eventbus messages
                       create_rt_client(URI, sock.writeHandlerID(), sock, sock_msg);
                   }
                   // A "rt_subscribe" / "rt_unsubscribe" subscription requests from this client
                   else if (sock_msg.getString("msg_type","oh crap").equals(Constants.SOCKET_RT_SUBSCRIBE))
                   {
                       // Add this connection to the client table
                       // and set up consumer for eventbus messages
                       create_rt_subscription(URI, sock.writeHandlerID(), sock_msg);
                   }
                   else if (sock_msg.getString("msg_type","oh crap").equals(Constants.SOCKET_RT_UNSUBSCRIBE))
                   {
                       // Add this connection to the client table
                       // and set up consumer for eventbus messages
                       remove_rt_subscription(URI, sock.writeHandlerID(), sock_msg);
                   }
                });

                // Assign handler for socket CLOSED
                sock.endHandler( (Void v) -> {
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                ": sock closed "+sock.writeHandlerID());
                        remove_rt_client(URI, sock.writeHandlerID());
                    });
          });

        router.route(URI+"/*").handler(sock_handler);

        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                           ": socket handler setup on '"+URI+"/*"+"'");

    } // end start_monitor()

    // **********************************************************************************************
    // **********************************************************************************************
    // ******************  Handle eventbus messages that a consumer has received ********************
    // **********************************************************************************************
    // **********************************************************************************************
    private void handle_message(String URI, String msg)
    {
        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+": eventbus message for "+URI);
        // Update the state of the relevant monitor, e.g. accumulate the latest and previous records
        monitors.update_state(URI, new JsonObject(msg));
        // Update the relevant clients that have subscribed
        monitors.update_clients(URI, new JsonObject(msg));
    }
    
    // **********************************************************************************************
    // ******************  Handle a client connection     *******************************************
    // **********************************************************************************************
    private void create_rt_client(String URI, String UUID, SockJSSocket sock, JsonObject sock_msg)
    {
        // create entry in client table for correct monitor
        monitors.add_client(URI, UUID, sock, sock_msg);

        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                ": adding client "+UUID+" with "+sock_msg.toString());
    }

    // **********************************************************************************************
    // ******************  Handle a subscription request  *******************************************
    // **********************************************************************************************
    private void create_rt_subscription(String URI, String UUID, JsonObject sock_msg)
    {
        // create entry in client table for correct monitor
        monitors.add_subscription(URI, UUID, sock_msg);

        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                ": subscribing client "+UUID+" with "+sock_msg.toString());
    }

    // **********************************************************************************************
    // ******************  Remove a subscription          *******************************************
    // **********************************************************************************************
    private void remove_rt_subscription(String URI, String UUID, JsonObject sock_msg)
    {
        // create entry in client table for correct monitor
        monitors.remove_subscription(URI, UUID, sock_msg);

        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                ": removing subscription from "+UUID+" with "+sock_msg.toString());
    }

    // **********************************************************************************************
    // ******************  Close a subscription request  ********************************************
    // **********************************************************************************************
    private void remove_rt_client(String URI, String UUID)
    {
        // remove entry in client table for correct monitor
        monitors.remove_client(URI, UUID);

        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                ": removed client "+UUID+" from monitor "+URI);
    }

    // ***************************************************************************************************
    // ***************************************************************************************************
    // ******************  The 'Monitor' data structures  ************************************************
    // ***************************************************************************************************
    // ***************************************************************************************************

    // A Monitor is instantiated for each feed for which real-time updated state is required.
    // The procedure 'update_state' is called each time a message arrives on the EventBus address assigned.
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
        public ArrayList<String> records_array;  // Json property location of the required data e.g. "request_data"
        public ArrayList<String> record_index;   // Json property (within records_array fields) containing sensor id
        public ClientTable clients;              // Set of sockets subscribing to this data

        private Hashtable<String, JsonObject> latest_records; // Holds data from the latest message 
        private Hashtable<String, JsonObject> previous_records; // Holds data from the previous message 

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
        public void update_state(JsonObject msg)
        {
            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+": update_state "+address);
            // This monitor may be for single records (i.e. msg = record)
            // or multiple records may be contained within nested 'records_array' object
            if (records_array.size() == 0)
            {
                // The whole message is considered the 'record'
                update_record(msg);
            }
            else
            {
                JsonArray records = get_records(msg);
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                           ": update_state processing "+records.size()+" records");
                for (int i=0; i<records.size(); i++)
                {
                    update_record(records.getJsonObject(i));
                }
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                           ": total "+latest_records.size()+" records");
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

        public void add_subscription(String UUID, JsonObject sock_msg)
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
                    }
                }
            }
            // now we have key_is_record_index we can add the subscription to the client
            clients.add_subscription(UUID, sock_msg, key_is_record_index);
        }

        public void remove_subscription(String UUID, JsonObject sock_msg)
        {
            clients.remove_subscription(UUID, sock_msg);
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
        public String to_html()
        {
            String html = "<p><b>"+address+"</b>, records in <b>"+array_to_string(records_array)+
                    "</b>, record identifier in <b>"+array_to_string(record_index)+
                    "</b></p>";
            html += "<p>Clients: "+clients.size()+"</p>";
            return html;
        }

    } // end class Monitor
        
    // ************************************************************************************************
    // ************************************************************************************************
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
        
    // ***************************************************************************************************
    // ***************************************************************************************************
    // ******************  The 'Client' data structures   ************************************************
    // ***************************************************************************************************
    // ***************************************************************************************************
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
            String subscription_id  = sock_msg.getString("subscription_id");
            if (subscription_id == null)
            {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                       ": missing subscription_id from "+UUID+" in "+sock_msg.toString());
                return;
            }

            Subscription s = new Subscription();

            s.subscription_id = subscription_id;

            s.key_is_record_index = key_is_record_index;

            s.msg = sock_msg;

            subscriptions.put(subscription_id, s);

            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                       ": Client.add_subscription "+UUID+ " " +sock_msg.toString()+" key_is_record_index="+
                       s.key_is_record_index);
            return;
        }

        // RTMonitor has received a 'rt_unsubscribe' message
        public void remove_subscription(JsonObject sock_msg)
        {
            String subscription_id = sock_msg.getString("subscription_id");
            if (subscription_id==null)
            {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                    ": Client.remove_subscription() for "+UUID+" called with subscription_id==null");
                return;
            }
            Subscription s = subscriptions.remove(subscription_id);
            if (s==null)
            {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                    ": Client.remove_subscription() for "+UUID+" subscription_id '"+subscription_id+
                    "' not found");
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
            // iterate the client subscriptions
            for (String subscription_id: subscriptions.keySet())
            {
                Subscription s = subscriptions.get(subscription_id);
                JsonArray filters = s.msg.getJsonArray("filters");
                if (filters == null)
                {
                    // no filters so send whole eventbus message
                    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                         ": sending entire eventbus message to client "+UUID);
                    sock.write(Buffer.buffer(eventbus_msg.toString()));
                }
                else
                {
                    // has filters, so iterate them
                    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                         ": sending filtered messages to  client "+UUID);
                    //DEBUG TODO
                    // add code to send filtered messages
                    for (int i=0; i<filters.size(); i++)
                    {
                        JsonObject filter = filters.getJsonObject(i);

                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                             ": testing filter "+filter.toString());

                        if (m.records_array.size() == 0)
                        {
                            // The whole message is considered the 'record'
                            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                       ": Client.update apply filter to whole eventbus msg");

                            // test the filter on the eventbus_msg and send on websocket if it passes
                            if (test_filter(filter, eventbus_msg))
                            {
                                // filter succeeded, so send whole message
                                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                       ": Client.update filter succeeded, sending whole eventbus msg");
                                sock.write(Buffer.buffer(eventbus_msg.toString()));
                            }
                        }
                        else
                        {
                            // Extract the 'data records' from the eventbus message
                            JsonArray records = m.get_records(eventbus_msg);

                            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                       ": Client.update processing "+records.size()+" records");

                            // Prepare a JsonArray to hold the filtered records
                            JsonArray filtered_records = new JsonArray();

                            // iterate the eventbus records and accumulate filtered records
                            for (int j=0; j<records.size(); j++)
                            {
                                JsonObject record = records.getJsonObject(j);
                                if (test_filter(filter, record))
                                {
                                    filtered_records.add(record);
                                }
                            }

                            if (filtered_records.size() == 0)
                            {
                                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                       ": Client.update 0 filtered records");
                            }
                            else
                            {
                                // Woo we have successfully found records within the filter scope
                                //DEBUG TODO actually send the "rt_data" packet
                                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                       ": Client.update sending "+filtered_records.size()+" filtered records");

                                // Build the data object to be sent in response to this subscription
                                JsonObject rt_data = new JsonObject();
                                rt_data.put("msg_type", Constants.SOCKET_RT_DATA);
                                rt_data.put("request_data", filtered_records);
                                sock.write(Buffer.buffer(rt_data.toString()));
                            }

                        }
                    }

                }
            }
        }

        // Test a JsonObject filter against a JsonObject data record
        private boolean test_filter(JsonObject filter, JsonObject record)
        {
            // Given a filter say { "key": "VehicleRef", "value": "SCNH-35224" }
            String key = filter.getString("key");
            if (key == null)
            {
                return false;
            }

            String value = filter.getString("value");
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

            return record_value.equals(value);
        }

    } // end class Client

    // Subscription to the eventbus message data
    // Each eventbus message may contain a JsonArray of multiple data records
    // Each client can have multiple subscriptions
    class Subscription {
        public String subscription_id;
        public boolean key_is_record_index; // optimization flag if subscription is filtering on 'primary key'
        public JsonObject msg; // 'rt_subscribe' websocket message when subscription was requested

        public String toString()
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

            // Create initially empty subscription list (will be indexed on "subscription_id")
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
            page += monitors.get(key).to_html();
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
