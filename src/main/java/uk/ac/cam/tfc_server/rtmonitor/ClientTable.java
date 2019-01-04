package uk.ac.cam.tfc_server.rtmonitor;

import java.util.*;
import java.time.*;
import java.time.format.*;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

import io.vertx.ext.web.handler.sockjs.SockJSSocket;

import uk.ac.cam.tfc_server.util.Constants;
import uk.ac.cam.tfc_server.util.Log;

    // ***************************************************************
    // ***************************************************************
    // Object to store data for all current socket connections
    class ClientTable {
        private Log logger;

        private String MODULE_NAME = "RTMonitor";
        private String MODULE_ID = "ClientTable";

        private Hashtable<String,Client> client_table;

        // initialize new SockInfo object
        ClientTable () {
            logger = new Log(RTMonitor.LOG_LEVEL);
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
            Client client = new Client(UUID, sock, sock_msg);

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
            // Do nothing if no clients
            if (client_table.size() == 0)
            {
                return;
            }

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
            String html = "<div>";
            for (String UUID: client_table.keySet())
            {
                html += client_table.get(UUID).toHtml(false);
            }
            html += "</div>";
            return html;
        }

    } // end class ClientTable

