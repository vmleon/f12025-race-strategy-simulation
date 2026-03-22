package udp.server;

public interface PacketTracker {

    void onPacketReceived(PacketHeader header, int packetSize);

    void printSummary();
}
