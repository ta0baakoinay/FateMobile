import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Live end-to-end verification of login -> char-server -> map-server against
 * the real running Fate MMO server, using the exact same packet layouts as
 * the Android client (net.LoginClient / net.CharServerClient /
 * net.MapServerClient). Uses a real test account provided by the operator.
 */
public final class LiveFullTest {
    public static void main(String[] args) throws Exception {
        String loginHost = args[0];
        int loginPort = Integer.parseInt(args[1]);
        String username = args[2];
        String password = args[3];

        // ---- Phase 1: login ----
        long accountId, loginId1, loginId2;
        int sex;
        List<CharServerEntry> charServers = new ArrayList<>();

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
            System.out.println("[login] sent CA_LOGIN for '" + username + "'");

            int opcode = readU16LE(in);
            if (opcode != 0x0AC4) {
                if (opcode == 0x083E) {
                    byte[] body = new byte[24];
                    in.readFully(body);
                    long err = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
                    System.out.println("[login] REFUSED, error=" + err + " — stopping.");
                    return;
                }
                System.out.println("[login] unexpected opcode 0x" + Integer.toHexString(opcode) + " — stopping.");
                return;
            }
            int packetLength = readU16LE(in);
            byte[] body = new byte[packetLength - 4];
            in.readFully(body);
            ByteBuffer b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
            loginId1 = b.getInt() & 0xFFFFFFFFL;
            accountId = b.getInt() & 0xFFFFFFFFL;
            loginId2 = b.getInt() & 0xFFFFFFFFL;
            b.getInt(); // last_ip
            byte[] lastLogin = new byte[26]; b.get(lastLogin);
            sex = b.get() & 0xFF;
            byte[] token = new byte[17]; b.get(token);
            System.out.println("[login] ACCEPTED — accountId=" + accountId + " sex=" + sex + " lastLogin=" + cstr(lastLogin));
            System.out.println("[login] login_id1=" + loginId1 + " login_id2=" + loginId2);

            while (b.remaining() >= 160) {
                byte[] ipBytes = new byte[4]; b.get(ipBytes);
                String ip = (ipBytes[0]&0xFF)+"."+(ipBytes[1]&0xFF)+"."+(ipBytes[2]&0xFF)+"."+(ipBytes[3]&0xFF);
                int port = b.getShort() & 0xFFFF;
                byte[] name = new byte[20]; b.get(name);
                int users = b.getShort() & 0xFFFF;
                b.getShort(); b.getShort(); // type, new_
                byte[] unknown = new byte[128]; b.get(unknown);
                charServers.add(new CharServerEntry(ip, port, cstr(name), users));
                System.out.println("[login] char-server: " + cstr(name) + " @ " + ip + ":" + port + " users=" + users);
            }
        }

        if (charServers.isEmpty()) {
            System.out.println("No char-servers in response — stopping.");
            return;
        }
        CharServerEntry target = charServers.get(0);

        Thread.sleep(500); // empirical test: does a small gap between login-close and char-connect matter?

        // ---- Phase 2: char-server ----
        System.out.println("\n[char] connecting to " + target.ip + ":" + target.port + " (as advertised by login-server)...");
        long charId = -1;
        String selectedCharName = null;
        int selectedSlot = -1;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(target.ip, target.port), 8000);
            socket.setSoTimeout(10000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            ByteBuffer buf = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN);
            buf.putShort((short) 0x0065);
            buf.putInt((int) accountId);
            buf.putInt((int) loginId1);
            buf.putInt((int) loginId2);
            buf.putShort((short) 0); // real 2-byte gap before sex (offset 14-15) — was the bug
            buf.put((byte) sex);
            out.write(buf.array());
            out.flush();
            System.out.println("[char] sent CH_ENTER accountId=" + accountId + " loginId1=" + loginId1 + " loginId2=" + loginId2 + " sex=" + sex);

            byte[] echo = new byte[4];
            in.readFully(echo);
            System.out.println("[char] account_id echo received");

            boolean sawSlotSummary = false, sawAcceptEnter = false;
            while (true) {
                int opcode = readU16LE(in);
                if (opcode == 0x006C) {
                    int err = in.readUnsignedByte();
                    System.out.println("[char] HC_REFUSE_ENTER err=" + err + " — stopping.");
                    return;
                } else if (opcode == 0x0081) {
                    int result = in.readUnsignedByte();
                    System.out.println("[char] auth-result(0x0081)=" + result + " — stopping.");
                    return;
                } else if (opcode == 0x082D) {
                    int len = readU16LE(in);
                    byte[] bd = new byte[len - 4]; in.readFully(bd);
                    System.out.println("[char] slot summary: producible=" + (bd[3]&0xFF) + " max=" + (bd[4]&0xFF));
                    sawSlotSummary = true;
                } else if (opcode == 0x006B) {
                    int len = readU16LE(in);
                    byte[] bd = new byte[len - 4]; in.readFully(bd);
                    ByteBuffer cb = ByteBuffer.wrap(bd).order(ByteOrder.LITTLE_ENDIAN);
                    cb.position(23); // skip MaxSlots/AvailableSlots/PremiumSlots/unknown[20]
                    int count = 0;
                    while (cb.remaining() >= 175) {
                        long cid = cb.getInt() & 0xFFFFFFFFL;
                        cb.getLong(); cb.getInt(); cb.getLong(); // exp, money, jobexp
                        int jobLevel = cb.getInt();
                        cb.getInt(); cb.getInt(); cb.getInt(); cb.getInt(); cb.getInt();
                        cb.getShort();
                        cb.getLong(); cb.getLong(); cb.getLong(); cb.getLong();
                        cb.getShort();
                        int job = cb.getShort() & 0xFFFF;
                        cb.getShort(); cb.getShort(); cb.getShort();
                        int level = cb.getShort() & 0xFFFF;
                        for (int i=0;i<7;i++) cb.getShort();
                        byte[] nameB = new byte[24]; cb.get(nameB);
                        for (int i=0;i<6;i++) cb.get();
                        int slot = cb.get() & 0xFF;
                        cb.get();
                        cb.getShort();
                        byte[] mapB = new byte[16]; cb.get(mapB);
                        cb.getInt(); cb.getInt(); cb.getInt(); cb.getInt();
                        cb.get(); // sex
                        String cname = cstr(nameB);
                        System.out.println("[char] character: " + cname + " job=" + job + " lvl=" + level + "/" + jobLevel + " slot=" + slot + " map=" + cstr(mapB));
                        if (charId == -1) { charId = cid; selectedCharName = cname; selectedSlot = slot; }
                        count++;
                    }
                    System.out.println("[char] HC_ACCEPT_ENTER: " + count + " character(s)");
                    sawAcceptEnter = true;
                } else if (opcode == 0x09A0) {
                    byte[] bd = new byte[4]; in.readFully(bd);
                } else if (opcode == 0x020D) {
                    int len = readU16LE(in);
                    byte[] bd = new byte[len - 4]; in.readFully(bd);
                    System.out.println("[char] block-character list consumed (" + bd.length + " bytes)");
                    break;
                } else {
                    System.out.println("[char] unexpected opcode 0x" + Integer.toHexString(opcode) + " while awaiting char list — stopping.");
                    return;
                }
            }

            if (!sawSlotSummary || !sawAcceptEnter || charId == -1) {
                System.out.println("[char] no character to select — stopping (create one first).");
                return;
            }

            // ---- select the first character ----
            System.out.println("\n[char] selecting '" + selectedCharName + "' (slot " + selectedSlot + ")...");
            ByteBuffer sel = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN);
            sel.putShort((short) 0x0066);
            sel.put((byte) selectedSlot);
            out.write(sel.array());
            out.flush();

            int selOpcode = readU16LE(in);
            if (selOpcode == 0x08B9) {
                byte[] pin = new byte[10]; in.readFully(pin);
                ByteBuffer pb = ByteBuffer.wrap(pin).order(ByteOrder.LITTLE_ENDIAN);
                long seed = pb.getInt() & 0xFFFFFFFFL;
                long pinAccountId = pb.getInt() & 0xFFFFFFFFL;
                int state = pb.getShort() & 0xFFFF;
                System.out.println("[char] HC_ACK_PINCODE seed=" + seed + " account=" + pinAccountId + " state=" + state
                        + " (pincode_enabled:no in conf, but server sent this anyway — live discrepancy, see docs) — reading next packet for the real select response...");
                selOpcode = readU16LE(in);
            }
            if (selOpcode == 0x0AC5) {
                byte[] bd = new byte[154]; in.readFully(bd);
                ByteBuffer mb = ByteBuffer.wrap(bd).order(ByteOrder.LITTLE_ENDIAN);
                long selCharId = mb.getInt() & 0xFFFFFFFFL;
                byte[] mapNameB = new byte[16]; mb.get(mapNameB);
                byte[] ipB = new byte[4]; mb.get(ipB);
                String mapIp = (ipB[0]&0xFF)+"."+(ipB[1]&0xFF)+"."+(ipB[2]&0xFF)+"."+(ipB[3]&0xFF);
                int mapPort = mb.getShort() & 0xFFFF;
                String mapName = cstr(mapNameB);
                System.out.println("[char] MAP REDIRECT: char=" + selCharId + " map=" + mapName + " @ " + mapIp + ":" + mapPort);

                // ---- Phase 3: map-server ----
                testMapServer(mapIp, mapPort, accountId, selCharId, loginId1, sex);
            } else if (selOpcode == 0x006C) {
                int err = in.readUnsignedByte();
                System.out.println("[char] select refused err=" + err);
            } else {
                System.out.println("[char] unexpected select response opcode 0x" + Integer.toHexString(selOpcode));
            }
        }
    }

    private static void testMapServer(String host, int port, long accountId, long charId, long loginId1, int sex) throws Exception {
        System.out.println("\n[map] connecting to " + host + ":" + port + "...");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 8000);
            socket.setSoTimeout(15000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            ByteBuffer buf = ByteBuffer.allocate(23).order(ByteOrder.LITTLE_ENDIAN);
            buf.putShort((short) 0x0436);
            buf.putInt((int) accountId);
            buf.putInt((int) charId);
            buf.putInt((int) loginId1);
            buf.putInt((int) System.currentTimeMillis());
            buf.putInt(0);
            buf.put((byte) sex);
            out.write(buf.array());
            out.flush();
            System.out.println("[map] sent CZ_ENTER (0x0436)");

            while (true) {
                int opcode = readU16LE(in);
                if (opcode == 0x0283) {
                    byte[] bd = new byte[4]; in.readFully(bd);
                    System.out.println("[map] session echo received, waiting for char-server round trip...");
                } else if (opcode == 0x0B18) {
                    byte[] bd = new byte[2]; in.readFully(bd); // ZC_EXTEND_BODYITEM_SIZE — not in original protocol doc, found live
                    System.out.println("[map] ZC_EXTEND_BODYITEM_SIZE consumed (undocumented extra packet in this sequence)");
                } else if (opcode == 0x02EB) {
                    byte[] bd = new byte[11]; in.readFully(bd);
                    ByteBuffer mb = ByteBuffer.wrap(bd).order(ByteOrder.LITTLE_ENDIAN);
                    long startTime = mb.getInt() & 0xFFFFFFFFL;
                    int b0 = mb.get() & 0xFF, b1 = mb.get() & 0xFF, b2 = mb.get() & 0xFF;
                    int x = ((b0 << 2) | (b1 >> 6)) & 0x3FF;
                    int y = (((b1 & 0x3F) << 4) | (b2 >> 4)) & 0x3FF;
                    int dir = b2 & 0x0F;
                    System.out.println("[map] ZC_ACCEPT_ENTER — MAP CONNECTION CONFIRMED. x=" + x + " y=" + y + " dir=" + dir + " startTime=" + startTime);
                    System.out.println("\nFULL LOGIN -> CHAR -> MAP CHAIN CONFIRMED AGAINST LIVE SERVER.");
                    return;
                } else if (opcode == 0x0074) {
                    int err = in.readUnsignedByte();
                    System.out.println("[map] ZC_REFUSE_ENTER err=" + err);
                    return;
                } else if (opcode == 0x0081) {
                    int err = in.readUnsignedByte();
                    System.out.println("[map] SC_NOTIFY_BAN err=" + err);
                    return;
                } else {
                    System.out.println("[map] unexpected opcode 0x" + Integer.toHexString(opcode));
                    return;
                }
            }
        }
    }

    private static final class CharServerEntry {
        final String ip; final int port; final String name; final int users;
        CharServerEntry(String ip, int port, String name, int users) { this.ip = ip; this.port = port; this.name = name; this.users = users; }
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

    private static String cstr(byte[] bytes) {
        int end = 0;
        while (end < bytes.length && bytes[end] != 0) end++;
        return new String(bytes, 0, end, StandardCharsets.US_ASCII);
    }
}
