import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * One-off live verification of the Phase 1 login handshake (mirrors
 * android/app/.../net/LoginClient.kt byte-for-byte) against the real,
 * running Fate MMO login server. Uses deliberately bogus credentials —
 * the goal is to confirm the wire framing/opcodes/struct layout documented
 * in docs/FATE_MMO_MOBILE_PROTOCOL.md §3 match a REAL server's actual
 * response, not to log into a real account.
 */
public final class LiveLoginTest {
    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String username = args.length > 2 ? args[2] : "definitely_not_a_real_account_xyz";
        String password = args.length > 3 ? args[3] : "not_a_real_password";

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 8000);
            socket.setSoTimeout(10000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            ByteBuffer buf = ByteBuffer.allocate(55).order(ByteOrder.LITTLE_ENDIAN);
            buf.putShort((short) 0x0064);
            buf.putInt(55); // clientVersion, per real clientinfo.xml
            buf.put(fixedField(username, 24));
            buf.put(fixedField(password, 24));
            buf.put((byte) 0); // clienttype
            out.write(buf.array());
            out.flush();
            System.out.println("Sent CA_LOGIN (0x0064), 55 bytes, to " + host + ":" + port);

            int opcode = readU16LE(in);
            System.out.println("Received opcode: 0x" + Integer.toHexString(opcode));

            if (opcode == 0x0AC4) {
                int packetLength = readU16LE(in);
                byte[] body = new byte[packetLength - 4];
                in.readFully(body);
                System.out.println("AC_ACCEPT_LOGIN — unexpected (bogus creds succeeded?!) len=" + packetLength);
            } else if (opcode == 0x083E) {
                byte[] body = new byte[24];
                in.readFully(body);
                ByteBuffer b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
                long error = b.getInt() & 0xFFFFFFFFL;
                System.out.println("AC_REFUSE_LOGIN (0x083E) — exactly as documented. error=" + error);
                System.out.println("PHASE 1 PROTOCOL CONFIRMED AGAINST LIVE SERVER.");
            } else if (opcode == 0x0081) {
                int result = in.readUnsignedByte();
                System.out.println("SC_NOTIFY_BAN (0x0081) result=" + result);
            } else {
                System.out.println("UNEXPECTED opcode — protocol doc may need re-checking for this server state.");
            }
        }
    }

    private static byte[] fixedField(String value, int length) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[length];
        System.arraycopy(bytes, 0, out, 0, Math.min(bytes.length, length - 1));
        return out;
    }

    private static int readU16LE(DataInputStream in) throws Exception {
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        return (b1 << 8) | b0;
    }
}
