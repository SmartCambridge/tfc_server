import java.io.*;
import uk.ac.cam.tfc_server.util.RTCrypto;

// E.g. cat token_file.json | java -cp target/tfc_server-3.6.3-fat.jar MakeToken H7tj6Rq98Xpswxyz

public class MakeToken {

    public static void main(String[] args) throws IOException {

        if (args.length == 0 || args[0].getBytes().length != 16)
        {
            System.out.println("16-byte key must be provided as argument");
            return;
        }

        System.out.println("Using key: "+args[0]+" length: " + args[0].getBytes("UTF-8").length );

        BufferedReader in = new BufferedReader(
            new InputStreamReader(System.in));
        String s;

        String token_string = "";

        while ((s = in.readLine()) != null) {
            token_string += s + "\n";
            System.out.println(s);
        }

        RTCrypto rt_crypto = new RTCrypto(null);

        String encrypted_token = rt_crypto.encrypt(args[0], token_string);

        System.out.println(encrypted_token);
    }

}
