package uk.ac.cam.tfc_server.rtmonitor;

import java.util.*;
import java.time.*;
import java.time.format.*;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.handler.sockjs.SockJSSocket;

import uk.ac.cam.tfc_server.util.Constants;
import uk.ac.cam.tfc_server.util.Log;

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

        private Log logger;

        private String MODULE_NAME = "RTMonitor";
        private String MODULE_ID = "Monitor";

        // Create a new Monitor, typically via MonitorTable.add(...)
        Monitor(String address, String records_array, String record_index) {

            logger = new Log(RTMonitor.LOG_LEVEL);

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

        // Add a client subscriber to this Monitor (on receipt of rt_connect message)
        public String add_client(String UUID, 
                                 SockJSSocket sock, 
                                 JsonObject sock_msg,
                                 RTToken token)
        {
            // a simple add of the client
            return clients.add(UUID, sock, sock_msg, token);
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
        public void update_clients(JsonObject eventbus_msg)
        {
            // Note this monitor contains the updated 'state' so we pass this monitor to the clients 
            clients.update(eventbus_msg, this);
        }

        // Given an EventBus message, return the JsonArray containing the data records
        public JsonArray get_records(JsonObject msg)
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
            String html = "<p>Subscribes to eventbus: <b>"+address+"</b></p>"+
                    "<p>Data records in message property: <b>"+array_to_string(records_array)+"</b></p>"+
                    "<p>Record sensor identifier property: <b>"+array_to_string(record_index)+"</b></p>";
            html += "<p>This Monitor has <b>"+clients.size()+"</b> client(s)</p>";
            html += clients.toHtml();
            return html;
        }

    } // end class Monitor
        
