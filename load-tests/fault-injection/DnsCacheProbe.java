import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves one host name like a connection pool does: once to fill the JVM address cache, nothing for
 * IDLE seconds, then it waits for the file /tmp/go (the runner creates it right after it took the target
 * down, like a broken connection that makes the pool reconnect) and looks the name up every 100 ms for
 * WATCH seconds. Prints one line per lookup: epoch milliseconds, the result and the IP.
 */
public class DnsCacheProbe {
    public static void main(String[] arguments) throws Exception {
        String host = arguments[0];
        long idleMillis = Long.parseLong(arguments[1]) * 1000;
        long watchMillis = Long.parseLong(arguments[2]) * 1000;
        InetAddress first = InetAddress.getByName(host);
        System.out.println("first " + first.getHostAddress());
        Thread.sleep(idleMillis);
        System.out.println("idle-over");
        System.out.flush();
        while (!Files.exists(Path.of("/tmp/go"))) {
            Thread.sleep(5);
        }
        long begin = System.nanoTime();
        while ((System.nanoTime() - begin) / 1_000_000 < watchMillis) {
            long elapsed = System.currentTimeMillis();
            try {
                String address = InetAddress.getByName(host).getHostAddress();
                System.out.println(elapsed + " ok " + address);
            } catch (UnknownHostException error) {
                System.out.println(elapsed + " unknown");
            }
            System.out.flush();
            Thread.sleep(100);
        }
    }
}
