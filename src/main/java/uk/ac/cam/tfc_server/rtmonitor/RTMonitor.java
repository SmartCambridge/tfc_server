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
import io.vertx.core.MultiMap; // for websocket headers

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
import uk.ac.cam.tfc_server.util.RTCrypto;

public class RTMonitor extends AbstractVerticle {

    private final String VERSION = "1.24";
    
    // from config()
    public static int LOG_LEVEL;             // optional in config(), defaults to Constants.LOG_INFO
    private String MODULE_NAME;       // config module.name - normally "msgrouter"
    private String MODULE_ID;         // config module.id - unique for this verticle
    private String EB_SYSTEM_STATUS;  // config eb.system_status
    private String EB_MANAGER;        // config eb.manager

    private int HTTP_PORT;            // config rtmonitor.http.port
    
    private String BASE_URI; // used as template parameter for web pages, built from config()
    
    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 25;
    private final int SYSTEM_STATUS_RED_SECONDS = 35;

    private final int SYSTEM_PURGE_SECONDS = 5*60; // check for client purge every 5 mins

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
    
        logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": V"+VERSION+
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

                response.end(page_html("home", routingContext));
            });
        logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": serving homepage at "+BASE_URI+"/home");

        router.route(BASE_URI+"/client/:id").handler( routingContext -> {

                HttpServerResponse response = routingContext.response();
                response.putHeader("content-type", "text/html");

                response.end(page_html("client", routingContext));
            });

        // iterate through all the monitors to be started
        for (int i=0; i<START_MONITORS.size(); i++)
        {
            start_monitor(START_MONITORS.getJsonObject(i), router);
        }

        http_server.requestHandler(router::accept).listen(HTTP_PORT);

        // set up periodic 'client purge' to clear out clients 
        vertx.setPeriodic(SYSTEM_PURGE_SECONDS * 1000 ,id -> {
            purge_clients();
        });

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

                MultiMap headers = sock.headers();

                if (headers != null)
                {
                    for (String header_name : headers.names())
                    {
                        String header_values = String.join(";; ", headers.getAll(header_name));
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                            ": Header "+header_name+": "+header_values);
                    }
                }
                else
                {
                    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                            ": No Sock headers");
                }

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
                        String token_hash = check_token(sock_msg, headers);

                        if (token_hash == null)
                        {
                            send_nok(sock,"","bad connect");
                            return;
                        }

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
    public static void send_nok(SockJSSocket sock, String request_id, String comment)
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
    // *************  Check an incoming rt_token  **********************************************
    // *****************************************************************************************
    private String check_token(JsonObject sock_msg, MultiMap headers)
    {
        JsonObject client_data = sock_msg.getJsonObject("client_data", null);

        if (client_data == null)
        {
            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                ": bad client_data");
            return null;
        }

        String token_string = client_data.getString("rt_token",null);

        if (token_string == null || token_string.length() <= 32)
        {
            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                ": bad rt_token");
            return null;
        }

        RTCrypto rt_crypto = new RTCrypto();

        JsonObject token = rt_crypto.rt_token(token_string);

        // use the encrypted chars [16:32] as a unique hash
        String token_hash = token_string.substring(16,32);

        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
            ": sock connect rt_token "+token_hash+" "+token.encodePrettily());

        // check origin from headers against origin pattern list in token
        
        // Get 'Origin' in client websocket connect header
        String client_origin = headers.get("Origin");

        if (client_origin == null)
        {
            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                ": bad client origin");
            return null;
        }

        // Get list of permitted origin patterns from token.Origins
        JsonArray origin_patterns = token.getJsonArray("origin",null);

        if (origin_patterns == null || origin_patterns.size() == 0)
        {
            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                ": bad rt_token origin pattern list");
            return null;
        }

        boolean origin_matches = false;

        for (int i = 0; i < origin_patterns.size(); i++)
        {
            String origin = null;
            try
            {
                origin = origin_patterns.getString(i);
            }
            catch (ClassCastException e)
            {
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                    ": bad rt_token origin["+i+"]");
                return null;
            }

            if (origin == null)
            {
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                    ": bad rt_token origin");
                return null;
            }
            if (client_origin.matches(origin))
            {
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                    ": client origin match with \""+origin+"\"");
                origin_matches = true;
                break;
            }
        }

        if (!origin_matches)
        {
            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                ": no client match in Origin list");
            return null;
        }

        return token_hash;
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
    // *************  Purge clients                  *******************************************
    // *****************************************************************************************
    private void purge_clients()
    {
        // get the current time, will check the clients against this
        ZonedDateTime now = ZonedDateTime.now(Constants.PLATFORM_TIMEZONE);

        for (String key: monitors.keySet())
        {
            ClientTable clients = monitors.get(key).clients;

            for (String UUID: clients.keySet())
            {
                Client c = clients.get(UUID);

                Duration connected_time = Duration.between(c.created, now);

                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                    ": purge test "+UUID+" "+connected_time.getSeconds());

                // kick all clients older than 36 hours
                if (connected_time.getSeconds() > 36*60*60)
                {
                    c.sock.close(); // disconnect this client

                    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                        ": purged client rtroute"+UUID+" "+connected_time.getSeconds());
                }

                // test case: kick rtroute after 1 hour
                else if (c.client_data.getString("rt_client_id","").equals("rtroute"))
                {
                    if (connected_time.getSeconds() > 60*60)
                    {
                        c.sock.close(); // disconnect this client

                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                            ": purged client rtroute"+UUID+" "+connected_time.getSeconds());
                    }
                }

                // smartpanel displays client_id starts with "--", kick after 10 mins
                else if (c.client_data.getString("rt_client_id","").startsWith("--"))
                {
                    if (connected_time.getSeconds() > 10*60)
                    {
                        c.sock.close(); // disconnect this client

                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                            ": purged client smartpanel display"+UUID+" "+connected_time.getSeconds());
                    }
                }
            }
        }
        return;
    }

    public static String format_date(ZonedDateTime d)
    {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd").format(d);
    }

    public static String format_time(ZonedDateTime d)
    {
        return DateTimeFormatter.ofPattern("HH:mm").format(d);
    }

    // provide page as HTML string
    private String page_html(String page_name, RoutingContext rc)
    {
        if (page_name.equals("home"))
        {
            return home_page();
        }

        if (page_name.equals("client"))
        {
            String id = rc.request().getParam("id");
            return client_page(id);
        }

        return "<html><body>Page not found</body></html>";
    }

    private String client_page(String id)
    {
        if (id == null)
        {
            return "<html><body>No Client </body></html>";
        }
        // iterate the monitors to find the client
        Client c = null;
        Set<String> keys = monitors.keySet();
        for (String key: keys)
        {
            c = monitors.get(key).clients.get(id);
            if (c != null)
            {
                break;
            }
        }
        String page = "<html><head><title>RTMonitor V"+VERSION+"</title>";
        page += "<style>";
        page += "body { font-family: sans-serif;}";
        page += "p { margin-left: 30px; }";
        page += "</style></head>";
        page += "<body>";
        page += "<h1>Adaptive City Platform: ";
        page += "RTMonitor V"+VERSION+": "+MODULE_NAME+"."+MODULE_ID+"</h1>";
        if (c == null)
        {
             page += "No client found";
        }
        else
        {
            page += c.toHtml(true); // full = true
        }
        page += "</body></html>";

        return page;
    }

    // String content of this verticle 'home' page
    private String home_page()
    {
        String page = "<html><head><title>RTMonitor V"+VERSION+"</title>";
        page += "<style>";
        page += "body { font-family: sans-serif;}";
        page += "p { margin-left: 30px; }";
        page += "</style></head>";
        page += "<body>";
        page += "<h1>Adaptive City Platform: ";
        page += "RTMonitor V"+VERSION+": "+MODULE_NAME+"."+MODULE_ID+"</h1>";
        page += "<p>BASE_URI="+BASE_URI+"</p>";
        page += "<p>This RTMonitor has "+monitors.size()+" monitor(s):</p>"; 

        // iterate the monitors
        Set<String> keys = monitors.keySet();
        for (String key: keys)
        {
            page += "<div><h3>Monitor "+key+"</h3>";
            page += monitors.get(key).toHtml();
            page += "</div>";
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
