package uk.ac.cam.tfc_server.rtmonitor;

import java.util.*;
import java.time.*;
import java.time.format.*;

import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.handler.sockjs.SockJSSocket;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.SocketAddress;

import uk.ac.cam.tfc_server.util.Constants;
import uk.ac.cam.tfc_server.util.Log;

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
                                                             //
                                       // Client info received on connection:
        public RTToken token;          // Valid RTToken created on connection
        public JsonObject client_data; // rt_client_id
                                       // rt_client_name
                                       // rt_client_url
                                       // rt_token
        public ZonedDateTime created;

        private JsonObject msg; // rt_connect message with which this client was created

        private MultiMap headers; // headers when this client was created

        private SocketAddress socket_address; // socket address when this client was created

        private Log logger;

        private String MODULE_NAME = "RTMonitor";
        private String MODULE_ID = "Client";

        // Construct a new Client 
        Client(String UUID, SockJSSocket sock, JsonObject msg, RTToken token)
        {
            this.UUID = UUID;

            this.sock = sock;

            this.token = token; 

            this.msg = msg;

            headers = sock.headers();

            socket_address = sock.remoteAddress();

            logger = new Log(RTMonitor.LOG_LEVEL);

            client_data = msg.getJsonObject("client_data", new JsonObject());

            // Create initially empty subscription list (will be indexed on "request_id")
            subscriptions = new Hashtable<String,Subscription>();

            created = ZonedDateTime.now(Constants.PLATFORM_TIMEZONE);
        }

        // RTMonitor has received a 'rt_subscribe' message, so add to relevant client
        public void add_subscription(JsonObject sock_msg, boolean key_is_record_index)
        {            
            String request_id  = sock_msg.getString("request_id");
            if (request_id == null)
            {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                       ": missing request_id from "+UUID+" in "+sock_msg.toString());
                // No request_id, so send rt_nok message and return
                RTMonitor.send_nok(sock, "no request_id given", "no request_id given in subscription");
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
                RTMonitor.send_nok(sock, "no request_id given", "no request_id given in unsubscribe request");
                return;
            }
            Subscription s = subscriptions.remove(request_id);
            if (s==null)
            {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                    ": Client.remove_subscription() for "+UUID+" request_id '"+request_id+
                    "' not found");
                // send rt_nok message and return
                RTMonitor.send_nok(sock, request_id, "request_id failed to match existing subscription");
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
                        // update count for this subscription
                        s.record_count += 1;
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

                    // If we have a set of filtered records we can send as "rt_data" on client socket
                    if (filtered_records.size() > 0)
                    {
                        // updated accumulated record count for the current subscription
                        s.record_count += filtered_records.size();

                        // Woo we have successfully found records within the filter scope
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                               ": Client.update sending "+filtered_records.size()+
                               " filtered records (subscription total "+s.record_count+")");

                        // Build the data object to be sent in response to this subscription
                        JsonObject rt_data = new JsonObject();
                        rt_data.put("msg_type", Constants.SOCKET_RT_DATA);
                        rt_data.put("request_data", filtered_records);
                        rt_data.put("request_id", request_id);
                        sock.write(Buffer.buffer(rt_data.toString()));
                    }
                    else
                    {
                        // If we have NO filtered records then do nothing (and move on to next subscription)
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                               ": Client.update 0 filtered records"+
                               " (subscription total "+s.record_count+")");
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
                RTMonitor.send_nok(sock, "no request_id given", "no request_id given in request");
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
                RTMonitor.send_nok(sock, request_id, "request 'filters' property not valid JsonArray");
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
                RTMonitor.send_nok(sock, request_id, "request 'options' property not valid JsonArray");
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

        // Return block of info about this client as HTML
        // full = true: return full details including subscriptions
        // full = false: only return summary
        public String toHtml(boolean full)
        {
            // Note these values are given in the rt_connect message 'client_data' property
            // so we don't particularly trust them.
            String client_id = client_data.getString("rt_client_id","unknown-client-id");
            String client_name = client_data.getString("rt_client_name","unknown-client-name");
            String client_url = client_data.getString("rt_client_url","unknown-client-url");
            String layout_name = client_data.getString("layout_name","no layout");
            String layout_owner = client_data.getString("layout_owner","-");
            String display_name = client_data.getString("display_name","no display");
            String display_owner = client_data.getString("display_owner","-");
            
            String html = "<div class='client'>";
            html += "<h3><a href='client/"+UUID+"'>Client: "+client_name+"</a></h3>";
            html += "<p><b>Client ref: </b>"+client_id;
            html += " \""+layout_name+"\" ("+layout_owner+") --";
            html += " \""+display_name+"\" ("+display_owner+")";
            html += "</p>";
            html += "<p><b>Connected: </b>"+RTMonitor.format_date(created)+"&nbsp;"+RTMonitor.format_time(created)+", ";

            // iterate the client subscriptions to get total record count
            int record_count = 0;
            for (String request_id: subscriptions.keySet())
            {
                record_count += subscriptions.get(request_id).record_count;
            }
            html += "<b>Records sent: </b>"+record_count+", ";

            int subscription_count = subscriptions.size();
            html += "<b>Subscriptions: </b>"+subscription_count+"</p>";

            html += "<p><b>Url: </b><a href='"+client_url+"'>"+client_url+"</a></p>";

            if (full)
            {
                // Client websocket UUID
                html += "<p><b>Client websocket UUID: </b>"+UUID+"</p>";

                // Http request headers at connect time
                html += "<p><b>Http connect headers:</b>";

                for (String name : headers.names()) 
                {
                    html += "<br/>" + name + " = ";        
                    List<String> values = headers.getAll(name);
                    for (String value : values)
                    {
                        html += "\"" + value + "\" ";
                    }
                }
                html += "</p>";

                html += token.toHtml();
                        
                html += "<table>";
                for (String request_id: subscriptions.keySet())
                {
                    html += subscriptions.get(request_id).toHtml();
                }
                html += "</table>";
            } // end full listing
            html += "</div>";
            return html;
        }

    } // end class Client

