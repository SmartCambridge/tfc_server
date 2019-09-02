package uk.ac.cam.tfc_server.feedmaker;

//**********************************************************************
//**********************************************************************
//   ParseParkingDatex.java
//
// Convert the data read from the http 'get' of DatexII XML for parking occupancy
// into an Adaptive City Platform eventbus message.
//
// Author: Ian Lewis ijl20@cam.ac.uk
//
//**********************************************************************
//**********************************************************************

// java classes
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.w3c.dom.Node;
import org.w3c.dom.Element;
import java.io.File;
import java.io.IOException;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.SAXException;
import org.xml.sax.InputSource;
import java.io.StringReader;

// vertx classes
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.buffer.Buffer;

// other tfc_server classes
import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;

public class ParseParkingDatex implements FeedParser {

    private String area_id;

    private JsonObject config;

    private Log logger;

    DocumentBuilderFactory doc_factory;

    DocumentBuilder doc_builder;

    ParseParkingDatex(JsonObject config, Log logger)
    {
        this.config = config;

        this.area_id = config.getString("area_id","");

        this.logger = logger;

        try
        {
            doc_factory = DocumentBuilderFactory.newInstance();

            doc_builder = doc_factory.newDocumentBuilder();
        }
        catch (javax.xml.parsers.ParserConfigurationException e)
        {
            logger.log(Constants.LOG_FATAL, "ParseParkingDatex XML parser load failure");
        }

        logger.log(Constants.LOG_DEBUG, "ParseParkingDatex started");
    }

    // Here is where we try and parse the XML source and return a JsonArray
    public JsonObject parse(Buffer buf) throws Exception
    {

        logger.log(Constants.LOG_DEBUG, "ParseParkingDatex.parse() called");

        // will throw exception on bad parse, caught by FeedMaker
        Document doc = doc_builder.parse(new InputSource(new StringReader(buf.toString())));

        NodeList parking_areas = doc.getElementsByTagName("parkingArea");

        for (int i = 0; i < parking_areas.getLength(); i++)
        {
            Node parking_area = parking_areas.item(i);
            String parking_area_id = parking_area.getAttributes().getNamedItem("id").getNodeValue();
            logger.log(Constants.LOG_DEBUG, "ParseParkingDatex parkingArea "+i+" "+parking_area_id);
        }

        JsonArray records = new JsonArray();

        logger.log(Constants.LOG_DEBUG, "ParseParkingDatex xml record");

        JsonObject json_record = new JsonObject();
        json_record.put("feed_data", buf.getBytes());
        records.add(json_record);
        JsonObject msg = new JsonObject();
        msg.put("request_data", records);
        return msg;
    }

} // end ParseParkingDatex

