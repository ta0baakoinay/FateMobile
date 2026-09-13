import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Login -> char-server -> create a character (CH_MAKE_CHAR, 0x0A39). */
public final class CreateCharTest {
    public static void main(String[] args) throws Exception {
        String loginHost = args[0];
        int loginPort = Integer.parseInt(args[1]);
        String username = args[2];
        String password = args[3];
        String charName = args[4];

        long accountId, loginId1, loginId2;
        int sex;
        String charHost = null;
        int charPort = 0;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(loginHost, loginPort), 8000);
            socket.setSoTimeout(10000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            ByteBuffer buf = ByteBuffer.allocate(55).order(ByteOrder.LITTLE_ENDIAN);
            buf.putShort((short) 0x0064);
            buf.putInt(55);
            buf.put(fixedField(username, 24));
            buf.put(fixedField(password, 24));
            buf.put((byte) 0);
            out.write(buf.array());
            out.flush();

            int opcode = readU16LE(in);
            if (opcode != 0x0AC4) {
                System.out.println("login failed, opcode=0x" + Integer.toHexString(opcode));
                return;
            }
            int packetLength = readU16LE(in);
            byte[] body = new byte[packetLength - 4];
            in.readFully(body);
            ByteBuffer b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
            loginId1 = b.getInt() & 0xFFFFFFFFL;
            accountId = b.getInt() & 0xFFFFFFFFL;
            loginId2 = b.getInt() & 0xFFFFFFFFL;
            b.getInt();
            byte[] lastLogin = new byte[26]; b.get(lastLogin);
            sex = b.get() & 0xFF;
            byte[] token = new byte[17]; b.get(token);
            byte[] ipBytes = new byte[4]; b.get(ipBytes);
            charHost = (ipBytes[0]&0xFF)+"."+(ipBytes[1]&0xFF)+"."+(ipBytes[2]&0xFF)+"."+(ipBytes[3]&0xFF);
            charPort = b.getShort() & 0xFFFF;
            System.out.println("[login] OK accountId=" + accountId + " char-server=" + charHost + ":" + charPort);
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(charHost, charPort), 8000);
            socket.setSoTimeout(10000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            ByteBuffer buf = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN);
            buf.putShort((short) 0x0065);
            buf.putInt((int) accountId);
            buf.putInt((int) loginId1);
            buf.putInt((int) loginId2);
            buf.putShort((short) 0);
            buf.put((byte) sex);
            out.write(buf.array());
            out.flush();

            byte[] echo = new byte[4]; in.readFully(echo);

            while (true) {
                int opcode = readU16LE(in);
                if (opcode == 0x006C) { System.out.println("HC_REFUSE_ENTER err=" + in.readUnsignedByte()); return; }
                if (opcode == 0x0081) { System.out.println("auth-result=" + in.readUnsignedByte()); return; }
                if (opcode == 0x082D || opcode == 0x006B) {
                    int len = readU16LE(in);
                    in.readFully(new byte[len - 4]);
                } else if (opcode == 0x09A0) {
                    in.readFully(new byte[4]);
                } else if (opcode == 0x020D) {
                    int len = readU16LE(in);
                    in.readFully(new byte[len - 4]);
                    break;
                } else {
                    System.out.println("unexpected opcode 0x" + Integer.toHexString(opcode));
                    return;
                }
            }

            System.out.println("[char] creating character '" + charName + "'...");
            ByteBuffer mk = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN);
            mk.putShort((short) 0x0A39);
            mk.put(fixedField(charName, 24));
            mk.put((byte) 0); // slot
            mk.putShort((short) 0); // hair_color
            mk.putShort((short) 0); // hair_style
            mk.putShort((short) 0); // start_job
            mk.putShort((short) 0); // unknown
            mk.put((byte) sex);
            out.write(mk.array());
            out.flush();

            int mkOpcode = readU16LE(in);
            if (mkOpcode == 0x08B9) {
                in.readFully(new byte[10]);
                System.out.println("(consumed unsolicited HC_ACK_PINCODE, reading next packet)");
                mkOpcode = readU16LE(in);
            }
            if (mkOpcode == 0x0B6F) {
                byte[] body = new byte[175]; in.readFully(body);
                System.out.println("CHARACTER CREATED SUCCESSFULLY (HC_ACCEPT_MAKECHAR).");
            } else if (mkOpcode == 0x006E) {
                System.out.println("Create refused, err=" + in.readUnsignedByte());
            } else {
                System.out.println("unexpected create response opcode 0x" + Integer.toHexString(mkOpcode));
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
